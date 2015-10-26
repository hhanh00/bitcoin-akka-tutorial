scalaVersion := "2.11.7"

version := "0.1.0-SNAPSHOT"

lazy val macros = Project("bitcoin-akka-tutorial-macros", file("."))

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value
)

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full)
