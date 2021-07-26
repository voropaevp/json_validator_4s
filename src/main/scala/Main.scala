import cats.effect._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.blaze.server._

import scala.concurrent.ExecutionContext.global
import fs2._
import org.http4s.dsl.Http4sDsl
import cats.data.EitherK
import cats.syntax._
import cats.implicits._

object Main extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    JsonValidator.serverStream[IO].compile.drain.as(ExitCode.Success)
}

object JsonValidator {
  def serverStream[F[_] : Async]: Stream[F, ExitCode] =
    BlazeServerBuilder[F](global)
      .bindHttp(port = 8881, host = "127.0.0.1")
      .withHttpApp(JsonValidatorRoutes[F]().routes.orNotFound)
      .serve
}

case class JsonValidatorRoutes[F[_] : Sync]() extends Http4sDsl[F] {


  type SchemaId = Int

  val routes: HttpRoutes[F] =
    HttpRoutes.of[F] {
      case POST -> Root / "schema" / IntVar(schemaId) => Ok("ping")
      case GET -> Root / "schema" / IntVar(schemaId) => Ok("ping")
      case GET -> Root / "validate" / IntVar(schemaId) => Ok("ping")

    }
}
