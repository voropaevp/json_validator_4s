name := "json_validator_4s"
resolvers += "jitpack".at("https://jitpack.io")

version := "0.1"
scalaVersion := "2.13.6"

val http4sVersion = "1.0.0-M23"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion % Test
)

libraryDependencies ++= Seq(
  // Optional for auto-derivation of JSON codecs
  "io.circe" %% "circe-generic" % "0.14.1",
  // Optional for string interpolation to JSON model
  "io.circe" %% "circe-literal" % "0.14.1",
  "io.circe" %% "circe-parser" % "0.14.1",
  "io.circe" %% "circe-json-schema" % "0.1.0"
)

libraryDependencies += "is.cir" %% "ciris" % "2.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % "test"
libraryDependencies += "org.typelevel" %% "cats-effect-testing-scalatest" % "1.1.1" % Test

Test / fork := true
Test / envVars := Map("SCHEMA_DIR" -> "testStore")

