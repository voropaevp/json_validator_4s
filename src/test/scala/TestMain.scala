import cats.data.Kleisli
import fs2.Stream
import org.http4s._
import io.circe.Json
import io.circe.Json.Folder
import org.http4s.circe._
import cats.effect._
import org.http4s.{HttpRoutes, QueryParamDecoder, Request, Response, Status}
import org.http4s.dsl.io._
import io.circe.generic.auto._
import io.circe.literal._
import fs2._
import org.http4s.implicits._
import cats.effect._
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec

import java.nio.file.{FileAlreadyExistsException, Paths, Files => JavaFiles, Path => JavaPath}
import scala.io.Source

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

  override def afterAll: Unit = cleanup()

  def encodeBody(s: String): Stream[IO, Byte] = Stream(s).through(text.utf8Encode).covary[IO]

  def encodeBody(s: Json): Stream[IO, Byte] = encodeBody(s.toString())

  var id = 0

  def getNextId: Int = {
    id += 1;
    id
  }


  val validator: Kleisli[IO, Request[IO], Response[IO]] = JsonValidator[IO](CirceJsonValidator, store).routes.orNotFound

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
      assert(System.getenv("SCHEMA_DIR") === "testStore")
    }
    "loads from validator.conf" in {
      import ValidatorConfig._
      config.load[IO] asserting (_ shouldBe Config("127.0.0.1", 8881, JavaPath.of("testStore")))
    }
  }


  "JsonValidator" - {
    "Other" - {
      "returns the 404 for invalid URL" in {
        validator.run(
          Request(method = Method.GET, uri = uri"/should_error")).map(_.status).asserting(_ shouldBe Status(404))
      }

      //  https://github.com/http4s/http4s/issues/23
      //
      //    "returns the 405 for invalid method" in {
      //      validator.run(
      //        Request(method = Method.POST, uri = uri"/validate/1")).asserting(_ shouldBe Status(405))
      //    }
    }
    "POST schema" - {
      "Invalid JSON upload should return error" in {
        val testId = getNextId
        validator.run(
          Request(method = Method.POST, uri = uri"/schema" / testId.toString, body = encodeBody(""""name": "Alice"}"""))).flatMap(
          _.asJson
        ).asserting(_ shouldBe
          json"""
          {
              "action" : "uploadSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Invalid JSON"
            }
            """)
      }

      "Readonly storage JSON should return Storage error" in {
        val testId = getNextId
        val file = store.resolve(testId.toString).toFile
        IO(file.createNewFile) >> IO(file.setReadOnly()) >> validator.run(
          Request(method = Method.POST, uri = uri"/schema" / testId.toString, body = encodeBody(validSchema))).flatMap(
          _.asJson
        ).asserting(_ shouldBe
          json"""
          {
              "action" : "uploadSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Storage error"
            }
            """)
      }

      "Valid JSON should return ok" in {
        val testId = getNextId
        validator.run(
          Request(method = Method.POST, uri = uri"/schema" / testId.toString, body = encodeBody(validSchema))).flatMap(
          _.asJson
        ).asserting(_ shouldBe
          json"""
          {
              "action" : "uploadSchema",
              "id" : $testId,
              "status" : "success"
            }
            """)
      }
    }

    "GET schema" - {
      "valid schema file should return itself" - {
        val testId: Int = getNextId
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, validSchema.noSpaces.getBytes)) >>
          validator.run(
            Request(method = Method.GET, uri = uri"/schema" / testId.toString)).flatMap(
            _.asJson
          ).asserting(_ shouldBe validSchema)
      }

      "invalid schema file should return invalid json error" - {
        val testId: Int = getNextId
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, ("{" + validSchema.noSpaces).getBytes)) >>
          validator.run(
            Request(method = Method.GET, uri = uri"/schema" / testId.toString)).flatMap(
            _.asJson
          ).asserting(_ shouldBe
            json"""
           {
              "action" : "getSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Invalid JSON"
            }
            """)
      }

      "missing file should return storage error" - {
        val testId: Int = 999
        validator.run(
          Request(method = Method.GET, uri = uri"/schema" / testId.toString)).flatMap(
          _.asJson
        ).asserting(_ shouldBe
          json"""
           {
              "action" : "getSchema",
              "id" : $testId,
              "status" : "error",
              "message" : "Storage Error"
            }
            """)
      }
    }
    "POST validate" - {
      "valid schema should return success" in {
        val testId: Int = 30
        val path = store.resolve(testId.toString)
        IO(JavaFiles.write(path, validSchema.noSpaces.getBytes)) >>
          validator.run(
            Request(method = Method.POST, uri = uri"/validate" / testId.toString, body = encodeBody(validSchemaInstance))).flatMap(
            _.asJson
          ).asserting(_ shouldBe
            json"""
              {
                "action": "validateDocument",
                "id": $testId,
                "status": "success"
              }
            """)
      }
    }
  }
}
