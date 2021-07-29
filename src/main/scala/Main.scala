import cats.effect._
import org.http4s.{HttpRoutes, MalformedMessageBodyFailure, QueryParamDecoder, Request, Response, StaticFile, Status}
import org.http4s.dsl.io._
import io.circe.generic.auto._
import io.circe.literal._
import org.http4s.implicits._
import org.http4s.blaze.server._
import io.circe._
import io.circe.schema.Schema
import io.circe.schema.ValidationError
import io.circe.parser._
import io.circe.syntax._
import ciris._

import scala.concurrent.ExecutionContext.global
import fs2._
import org.http4s.dsl.Http4sDsl
import cats.data.Validated.Valid
import cats.syntax._
import cats.data._
import cats.implicits._

import java.nio.file.{Files, Path => JavaPath, Paths => JavaPaths}

object ValidatorConfig {
  final case class Config(
                           host: String,
                           port: Int,
                           path: Path
                         )

  private def parsePath(pathStr: String): Either[ConfigError, JavaPath] = Either.catchNonFatal {
    val path = JavaPaths.get(pathStr)
    if (Files.notExists(path)) {
      Files.createDirectories(path)
      path
    } else if (Files.isWritable(path))
      path
    else
      throw new SecurityException(s"[$pathStr] is not writable")
  }.leftMap(_.getMessage)
    .leftMap(ConfigError.apply)


  implicit val posIntConfigDecoder: ConfigDecoder[String, Path] = ConfigDecoder.identity[String].map(parsePath)

  val config: ConfigValue[Effect, Config] =
    (env("hostname").or(prop("hostname")).default("127.0.0.1").as[String],
      env("port").or(prop("port")).default("8881").as[Int],
      env("schemaDir").or(prop("schemaDir")).default("store").as[Path]
      ).parMapN(Config)

}

import ValidatorConfig._

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    Stream.eval(config.load[IO])
      .flatMap(JsonValidator.serverStream[IO])
      .compile.drain.as(ExitCode.Success)
  }
}

object types {
  type SchemaId = Int
  type RawJson = String
  type ValidatedJson = ValidatedNel[Exception, Json]
}

import types._

trait StorageAlgebra[F[_], K, V] {
  def store(k: K, v: V): F[Unit]

  def get(k: K): F[Option[V]]
}


trait ValidatorAlgebra {
  def validateSchema(schema: Schema, string: String): ValidatedJson

  def validateJson(text: String): ValidatedJson

  def cleanNull(json: Json): Json
}

case object SchemaNotFoundException extends Exception

case object CirceJsonValidator extends ValidatorAlgebra {

  def validateSchema(schema: Schema, string: String): ValidatedJson =
    validateJson(string).andThen(json =>
      schema.validate(json).as(json))


  def validateJson(text: String): ValidatedJson = parse(text).toValidatedNel

  def cleanNull(json: Json): Json = json.deepDropNullValues

}

import org.http4s.circe.CirceEntityEncoder._

case class JsonValidator[F[_] : Async](validator: ValidatorAlgebra, schemaDir: JavaPath) extends Http4sDsl[F] {

  implicit val ValidatedJsonEncoder: Encoder[ValidatedJson] = Encoder.instance {
    case Valid(json) => json
    case Validated.Invalid(errors) =>
      json"""{
      "action": "validateDocument",
      "id": "config-schema",
      "status": "error",
      "message": ${errors.map(_.getMessage).toList.mkString("\n")}
     }"""
  }

  implicit val ParsedJsonEncoder: Encoder[Either[ParsingFailure, Unit]] = Encoder.instance {
    case Right(_) => json"""
        {
            "action": "uploadSchema",
            "status": "success"
        }
        """
    case Left(error) =>
      json"""{
      "action": "validateDocument",
      "status": "error",
      "message": ${error.message}
     }"""
  }

  import org.http4s.circe._
  import java.io._

  def outputStream(f: File): Resource[F, FileOutputStream] =

  // CountDownLatch may be required to handle high load
  def writeFile(id: SchemaId): Resource[F, FileOutputStream] = Resource.make {
    Async[F].delay(new FileOutputStream(schemaDir.resolve(id.toString).toFile))
  } { outStream =>
    Async[F].delay(outStream.close())
  }


  val routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case req@POST -> Root / "schema" / IntVar(schemaId) => req
        .asJson
        .map(_.deepDropNullValues)
        .flatMap(json =>
          writeFile(schemaId).use { fStream =>
            Async[F].delay(fStream.write(json.toString().getBytes("utf-8")))
          }.as(Ok(
            json"""{
                "action": "uploadSchema",
                "id": $schemaId,
                "status": "error",
                "message": "Invalid JSON"
            }"""))
        ).recover {
        case _: MalformedMessageBodyFailure => BadRequest(
          json"""{
                "action": "uploadSchema",
                "id": $schemaId,
                "status": "error",
                "message": "Invalid JSON"
            }""")


        case _: IOException => InternalServerError(
          json"""{
                "action": "uploadSchema",
                "id": $schemaId,
                "status": "error",
                "message": "Storage Error"
            }""")
        case ex: _ => InternalServerError(
          json"""{
                "action": "uploadSchema",
                "id": $schemaId,
                "status": "error",
                "message": ${ex.getMessage}
            }""")

      }


        .flatMap(x => Ok(x))

      case GET -> Root / "schema" / IntVar(schemaId)
      => Ok("ping")
      case GET -> Root / "validate" / IntVar(schemaId)
      => Ok("ping")

    }
}


object JsonValidator {

  def serverStream[F[_] : Async](cfg: Config): Stream[F, ExitCode] =
    BlazeServerBuilder[F](global)
      .bindHttp(cfg.port, cfg.host)
      .withHttpApp(JsonValidator(CirceJsonValidator, cfg.path).routes.orNotFound)
      .serve

}