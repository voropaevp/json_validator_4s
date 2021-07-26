name := "json_validator_4s"
resolvers += "jitpack".at("https://jitpack.io")

version := "0.1"
scalaVersion := "2.13.6"

val http4sVersion = "1.0.0-M23"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion
)

libraryDependencies +=  "io.circe" %% "circe-json-schema" % "0.1.0"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.9" % "test"
libraryDependencies += "org.typelevel" %% "cats-effect-testing-scalatest" % "1.1.1" % Test



