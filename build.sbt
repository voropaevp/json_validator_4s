name := "json_validator_4s"
resolvers += "jitpack".at("https://jitpack.io")

version := "0.1"
scalaVersion := "2.13.6"

val http4sVersion = "1.0.0-M23"
val circeVersion = "0.14.1"

// Web
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion % Test
)
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"


//Json
libraryDependencies ++= Seq(
  "io.circe" %% "circe-literal" % circeVersion,
  "io.circe" %% "circe-parser" % circeVersion,
  "io.circe" %% "circe-json-schema" % "0.1.0"
)

// Config
libraryDependencies += "is.cir" %% "ciris" % "2.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % Test
libraryDependencies += "org.typelevel" %% "cats-effect-testing-scalatest" % "1.1.1" % Test

// Testing ciris's params
Test / fork := true
Test / envVars := Map("SCHEMA_DIR" -> "testStore")


