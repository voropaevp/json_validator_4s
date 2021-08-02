import org.http4s.{Headers, HttpRoutes, MalformedMessageBodyFailure, MediaType, Response}
import org.http4s.blaze.server._
import org.http4s.implicits._
import org.http4s.circe._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.middleware.Logger
import org.http4s.headers.`Content-Type`
import io.circe._
import io.circe.parser._
import io.circe.literal._
import io.circe.syntax._
import io.circe.schema.{Schema, ValidationError}
import ciris._
import cats.data._
import cats.data.Validated.Valid
import cats.implicits._
import cats.effect._
import fs2._

import java.io._
import java.nio.file.{Files, Path => JavaPath, Paths => JavaPaths}
import scala.concurrent.ExecutionContext.global

/** [[ciris]]'s config handling. This is environment based config without the file.
 */
object ValidatorConfig {

  final case class Config(
                           host: String,
                           port: Int,
                           path: JavaPath
                         )

  /** Checks existence and permissions on provides directory. Creates one if doesn't exist.
   *
   * @param pathStr path to json schema storage directory
   * @return Either of `nio.file.Path` or [[ConfigError]]
   */
  private def parseCreatePath(pathStr: String): Either[ConfigError, JavaPath] = Either.catchNonFatal {
    val path = JavaPaths.get(pathStr)
    if (Files.notExists(path)) {
      Files.createDirectories(path)
      path
    } else if (Files.isWritable(path))
      path
    else
      throw new SecurityException(s"[$pathStr] is not writable")
  }.leftMap(ex => ConfigError(ex.getMessage))

  implicit val posIntConfigDecoder: ConfigDecoder[String, JavaPath] = ConfigDecoder
    .identity[String]
    .mapEither((_, z) => parseCreatePath(z))

  /** config builder  from environment, then system prop, and finally defaults.
   */
  val validatorConfig: ConfigValue[Effect, Config] =
    (env("HOSTNAME").or(prop("HOSTNAME")).default("127.0.0.1").as[String],
      env("PORT").or(prop("PORT")).default("8881").as[Int],
      env("SCHEMA_DIR").or(prop("SCHEMA_DIR")).default("store").as[JavaPath]
      ).parMapN(Config)

}

import ValidatorConfig._


/** Base types for validator application */
object ValidatorTypes {
  type SchemaId = Int

  case object StorageError extends Exception("Storage error")

  case object InvalidJsonError extends Exception("Invalid JSON")

  case object UnknownError extends Exception("Unknown error")
}

/** All possible replies, excluding the one for 404. Since 404 can't be created from case class.  */
object ValidatorReplies {

  import ValidatorTypes._

  case class ReplyOk(action: String, id: SchemaId)

  case class ReplyError(action: String, id: SchemaId, error: Exception)

  case class ReplyErrors(action: String, id: SchemaId, errors: NonEmptyList[ValidationError])

  implicit val ValidationErrorEncoder: Encoder[ValidationError] = Encoder.encodeString.contramap[ValidationError](_.getMessage)

  implicit val ValidatorReplyOkEncoder: Encoder[ReplyOk] = Encoder.forProduct3(
    "id",
    "action",
    "status")(
    r => (r.id, r.action, "success")
  )

  implicit val ValidatorReplyErrorEncoder: Encoder[ReplyError] = Encoder.forProduct4(
    "id",
    "action",
    "message",
    "status")(
    r => (r.id, r.action, r.error.getMessage, "error")
  )

  implicit val ValidatorReplyErrorsEncoder: Encoder[ReplyErrors] = Encoder.forProduct4(
    "id",
    "action",
    "message",
    "status")(
    r => (r.id, r.action, r.errors.asJson, "error")
  )
}

/** Central class for the application.
 *
 * @param schemaDir base directory for json schema storage
 *
 *  See the tests for the detailed spec.
 *
 *  For brevity Validator and Storage features have not been abstracted into separate algebras/implementations.
 *
 */
case class JsonValidator[F[_] : Async](schemaDir: JavaPath) extends Http4sDsl[F] {

  import ValidatorTypes._
  import ValidatorReplies._


  /** Reads circe's json from file
   *
   * CountDownLatch may be required to handle high load
   *
   * @param schemaId Unique id
   * @return Resource with [[Json]], that clear down open file handles
   *
   * For something more complicated both [[readJson]] and [[writeJson]] should be abstracted into algebra/implementaion
   */
  private def readJson(schemaId: SchemaId): Resource[F, Json] =
    Resource.make {
      Async[F].delay(new FileInputStream(schemaDir.resolve(schemaId.toString).toFile))
    } { inStream =>
      Async[F].delay(inStream.close())
    }
      .map(out => new String(out.readAllBytes()))
      .map(parse)
      .map(_.toTry.get)


  /** Write circe's json from file
   *
   * CountDownLatch may be required to handle high load. Or Redis/Memcached even better.
   *
   * @param schemaId Unique id
   * @param json     circe's [[Json]]
   * @return Resource with [[Unit]], that clear down open file handles
   */
  private def writeJson(json: Json, schemaId: SchemaId): Resource[F, Unit] =
    Resource.make {
      Async[F].delay(new FileOutputStream(schemaDir.resolve(schemaId.toString).toFile))
    } { outStream =>
      Async[F].delay(outStream.close())
    }
      .map(_.write(json.noSpaces.getBytes("utf-8")))


  /** Intercepts all [[Throwable]] at the route edge
   *
   * @param serviceName base part of uri "schema" or "validate"
   * @param schemaId    id of resource.
   * @return Partial function that can be used as argument to handleWith
   */
  def recoverWithValidator(serviceName: String, schemaId: SchemaId): PartialFunction[Throwable, F[Response[F]]] = {
    case _: MalformedMessageBodyFailure =>
      BadRequest(ReplyError(serviceName, schemaId, InvalidJsonError))
    case _: ParsingFailure =>
      BadRequest(ReplyError(serviceName, schemaId, InvalidJsonError))
    case _: IOException =>
      InternalServerError(ReplyError(serviceName, schemaId, StorageError))
    case _: Throwable =>
      InternalServerError(ReplyError(serviceName, schemaId, UnknownError))
  }


  val routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req@POST -> Root / "schema" / IntVar(schemaId) =>
        req
          .asJson
          .map(_.deepDropNullValues)
          .map(j => {
            Schema.load(j)
            j
          })
          .flatMap(json => writeJson(json, schemaId).use_)
          .>>(Created(ReplyOk("uploadSchema", schemaId)))
          .recoverWith(recoverWithValidator("uploadSchema", schemaId))

      case GET -> Root / "schema" / IntVar(schemaId) =>
        readJson(schemaId)
          .use(schema => Ok.apply(schema))
          .recoverWith {
            case _: ParsingFailure =>
              InternalServerError(ReplyError("getSchema", schemaId, InvalidJsonError))
          }
          .recoverWith(recoverWithValidator("uploadSchema", schemaId))

      // schema cache would improve performance, but compromise consistency with filesystem
      case req@POST -> Root / "validate" / IntVar(schemaId) =>
        readJson(schemaId).use(schema =>
          req.asJson
            .map(_.deepDropNullValues)
            .map { subject =>
              Schema
                .load(schema)
                .validate(subject)
            }
        ).flatMap {
          case Valid(_) => Ok(ReplyOk("validateDocument", schemaId))
          case Validated.Invalid(errors) => BadRequest(ReplyErrors("validateDocument", schemaId, errors))
        }.recoverWith(recoverWithValidator("validateDocument", schemaId))
      case _ => Sync[F].pure {
        Response[F](
          NotFound,
          body = Stream(
            """
               {
                "status" : "error",
                "message": "Not found"
              }
              """.stripMargin).through(text.utf8Encode),
          headers = Headers(`Content-Type`(MediaType.application.json) :: Nil)
        )
      }
    }
}


object JsonValidator {
  def serverStream[F[_] : Async](cfg: Config): Stream[F, ExitCode] =
    BlazeServerBuilder[F](global)
      .bindHttp(cfg.port, cfg.host)
      .withHttpApp(Logger.httpApp(logHeaders = true, logBody = true)(JsonValidator(cfg.path)
        .routes
        .orNotFound
      ))
      .serve

}

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    Stream.eval(validatorConfig.load[IO])
      .flatMap(JsonValidator.serverStream[IO])
      .compile.drain.as(ExitCode.Success)
  }
}
