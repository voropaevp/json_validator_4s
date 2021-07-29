import io.circe.Json
import io.circe.literal._
import org.http4s.circe._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import cats.data.Kleisli
import cats.implicits._
import cats.effect._
import cats.effect.testing.scalatest.AsyncIOSpec
import fs2._
import org.http4s.headers.`Content-Type`
import org.scalatest.{Assertion, BeforeAndAfterAll}
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

import java.nio.file.{FileAlreadyExistsException, Files => JavaFiles, Path => JavaPath}


trait FileStorageSpec {

  def store: JavaPath = JavaPath.of("src", "test", "store")

  def cleanup(): Unit = {
    JavaFiles.walk(store)
      .map(_.toFile)
      .forEach { f =>
        f.setWritable(true)
        f.delete
      }

  }

  def initStore(): Unit = scala.util.control.Exception.ignoring(classOf[FileAlreadyExistsException]) {
    JavaFiles.createDirectories(store)
  }


}

class TestMain extends AsyncFreeSpec with AsyncIOSpec with Matchers with FileStorageSpec with BeforeAndAfterAll {

  override def beforeAll(): Unit = initStore()

  override def afterAll(): Unit = cleanup()

  def encodeBody(s: String): Stream[IO, Byte] = Stream(s).through(text.utf8Encode).covary[IO]

  def encodeBody(s: Json): Stream[IO, Byte] = encodeBody(s.toString())

  def makeRequest(method: Method, service: String): Request[IO] = Request(method, Uri.unsafeFromString(service))

  def makeRequest(method: Method, service: String, body: String): Request[IO] = Request(method,
    Uri.unsafeFromString(service),
    headers = Headers(`Content-Type`(MediaType.application.json)),
    body = encodeBody(body))

  def makeRequest(method: Method, service: String, body: Json): Request[IO] = Request(method,
    Uri.unsafeFromString(service),
    body = encodeBody(body))

  val validatorService: Kleisli[IO, Request[IO], Response[IO]] = JsonValidator[IO](store).routes.orNotFound

  def testResponseCode(request: Request[IO], statusRef: Status): IO[Assertion] = validatorService.run(request)
    .map(_.status)
    .asserting {
      _ shouldBe statusRef
    }


  def testResponse(request: Request[IO], statusRef: Status, bodyRef: Json): IO[Assertion] = validatorService.run(request)
    .flatMap(resp =>
      (IO(resp.status), resp.asJson).parTupled.asserting {
        case (status, body) =>
          status shouldBe statusRef
          body shouldBe bodyRef
        case _ => fail()
      }
    )


  val validSchemaInstance =
    json"""
      {
        "source": "/home/alice/image.iso",
        "destination": "/mnt/storage",
        "timeout": null,
        "chunks": {
          "size": 1024,
          "number": null
        }
      }
      """

  val invalidSchemaInstance =
    json"""
  {
    "source": "/home/alice/image.iso"
  }
  """
  val validSchema =
    json"""
      {
        "$$schema": "http://json-schema.org/draft-04/schema#",
        "type": "object",
        "properties": {
          "source": {
            "type": "string"
          },
          "destination": {
            "type": "string"
          },
          "timeout": {
            "type": "integer",
            "minimum": 0,
            "maximum": 32767
          },
          "chunks": {
            "type": "object",
            "properties": {
              "size": {
                "type": "integer"
              },
              "number": {
                "type": "integer"
              }
            },
            "required": ["size"]
          }
        },
        "required": ["source", "destination"]
      }
        """


  "Config" - {
    "Env variable preset" in {
      assert(System.getenv("SCHEMA_DIR") == "testStore")
    }
    "loads from validator.conf" in {
      import ValidatorConfig._
      validatorConfig.load[IO] asserting (_ shouldBe Config("127.0.0.1", 8881, JavaPath.of("testStore")))
    }
  }


  "JsonValidator" - {
    "Other" - {
      "returns the 404 for invalid URL" in {
        testResponse(
          Request(method = Method.GET, uri = uri"/should_error"),
          NotFound,
          json"""
                 {
                   "status" : "error",
                   "message": "Not found"
                }"""
        )
      }

      //  https://github.com/http4s/http4s/issues/23
      //
      //    "returns the 405 for invalid method" in {
      //      validator.run(
      //        Request(method = Method.POST, uri = uri"/validate/1")).asserting(_ shouldBe Status(405))
      //    }
    }

    "POST schema" - {
      "Invalid JSON upload should return Invalid JSON" in {
        val testId = 1
        testResponse(
          makeRequest(POST, s"/schema/$testId", """"name": "Alice"}"""),
          BadRequest,
          json"""
          {
              "action" : "uploadSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Invalid JSON"
            }
            """
        )
      }

      // That should be 204 right?
      "Readonly storage JSON should return 500 Storage error." in {
        val testId = 3
        val file = store.resolve(testId.toString).toFile
        IO(file.createNewFile) >> IO(file.setReadOnly()) >> testResponse(
          makeRequest(POST, s"/schema/$testId", validSchema),
          InternalServerError,
          json"""
          {
              "action" : "uploadSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Storage error"
            }
            """
        )
      }

      "Valid JSON should return 201 success" in {
        val testId = 4
        testResponse(
          makeRequest(POST, s"/schema/$testId", validSchema),
          Created,
          json"""
                {
                    "action" : "uploadSchema",
                    "id" : $testId,
                    "status" : "success"
                 }
                 """
        )
      }
    }

    "GET schema" - {
      "valid schema file should return 200 and valid schema" - {
        val testId: Int = 11
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, validSchema.noSpaces.getBytes)) >>
          testResponse(
            makeRequest(GET, s"/schema/$testId"),
            Ok,
            validSchema
          )
      }

      "invalid schema file should return invalid 500 Invalid JSON" - {
        val testId: Int = 12
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, ("{" + validSchema.noSpaces).getBytes)) >>
          testResponse(
            makeRequest(GET, s"/schema/$testId"),
            InternalServerError,
            json"""
           {
              "action" : "getSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Invalid JSON"
            }
            """
          )
      }

      "missing file should return 500 Storage Error" - {
        val testId: Int = 13
        testResponse(
          makeRequest(GET, s"/schema/$testId"),
          InternalServerError,
          json"""
           {
              "action" : "getSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Storage Error"
            }
            """
        )
      }
    }
    "POST validate" - {
      "Valid schema should return 200 success" in {
        val testId: Int = 20
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, validSchema.noSpaces.getBytes)) >>
          testResponse(
            makeRequest(POST, s"/validate/$testId", validSchemaInstance),
            Ok,
            json"""
                {
                    "action" : "validateDocument",
                    "id" : $testId,
                    "status" : "success"
                 }
                 """
          )
      }

      "Invalid schema should return validation error" in {
        val testId: Int = 21
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, validSchema.noSpaces.getBytes)) >>
          testResponse(
            makeRequest(POST, s"/validate/$testId", invalidSchemaInstance),
            BadRequest,
            json"""
              {
                "action": "validateDocument",
                "id": $testId,
                "status": "error",
                "message" : [
                  "#: required key [destination] not found"
                ]
              }
            """
          )
      }
    }
  }
}

