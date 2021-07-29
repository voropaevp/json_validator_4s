import cats.data.Kleisli
import cats.implicits._
import io.circe._
import io.circe.syntax._
import cats.effect._
import fs2.Stream
import org.http4s._
import org.http4s.circe._
import org.http4s.circe.CirceEntityEncoder._
import cats.effect._
import org.http4s.{HttpRoutes, QueryParamDecoder, Request, Response, Status}
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
import fs2._
import org.http4s.dsl.Http4sDsl
import cats.data.EitherK
import cats.data.Validated.Valid
import cats.syntax._
import cats.data._
import cats.implicits._
import org.http4s.dsl.io._
import org.http4s.implicits._
import cats.effect._
import org.http4s.client._
import org.http4s.client.dsl.io._
import org.scalatest._
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec


class TestMain extends AsyncFreeSpec with AsyncIOSpec with Matchers {
  //
  //  def check[A](actual: IO[Response[IO]],
  //               expectedStatus: Status,
  //               expectedBody: Option[A])(
  //                implicit ev: EntityDecoder[IO, A]
  //              ): Boolean = {
  //    val actualResp = actual.unsafeRunSync
  //    val statusCheck = actualResp.status == expectedStatus
  //    val bodyCheck = expectedBody.fold[Boolean](
  //      actualResp.body.compile.toVector.unsafeRunSync.isEmpty)( // Verify Response's body is empty.
  //      expected => actualResp.as[A].unsafeRunSync == expected
  //    )
  //    statusCheck && bodyCheck
  //  }

  val validator: Kleisli[IO, Request[IO], Response[IO]] = JsonValidator[IO](CirceJsonValidator).routes.orNotFound

  def encodeBody(s: String): Stream[IO, Byte] = Stream(s).through(text.utf8Encode).covary[IO]

  "JsonValidator" - {
    "returns the 404 for invalid URL" in {
      validator.run(
        Request(method = Method.GET, uri = uri"/should_error")).map(_.status).asserting(_ shouldBe Status(404))
    }

    // Not my bug https://github.com/http4s/http4s/issues/23

    //    "returns the 405 for invalid method" in {
    //      validator.run(
    //        Request(method = Method.POST, uri = uri"/validate/1")).asserting(_ shouldBe Status(405))
    //    }

    "returns the OK in schema" in {
      validator.run(
        Request(method = Method.GET, uri = uri"/schema/1")).flatMap(_.bodyText.compile.toVector).asserting(_.head shouldBe "ping")
    }

    "Invalid JSON should return config-error" in {
      validator.run(
        Request(method = Method.POST, uri = uri"/schema/2", body = encodeBody(""""name": "Alice"}"""))).flatMap(
        _.asJson
      ).asserting(_ shouldBe
        json"""
          {
              "action" : "uploadSchema",
              "id" : 2,
              "status" : "error",
              "message" : "Invalid JSON"
            }
            """)
    }

    //    "Saves the file in memory" in {
    //
    //    }

  }
}
