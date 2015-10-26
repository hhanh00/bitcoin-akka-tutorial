enablePlugins(JavaAppPackaging)

scalaVersion := "2.11.7"

version := "0.1.0-SNAPSHOT"

libraryDependencies ++= Seq(
	"com.typesafe.akka" %% "akka-actor" % "2.4.0",
	"com.typesafe.akka" %% "akka-slf4j" % "2.4.0",
	"org.apache.commons" % "commons-lang3" % "3.3.2",
	"commons-codec" % "commons-codec" % "1.10",
	"org.scalaz" %% "scalaz-core" % "7.1.3",
	"com.jsuereth" %% "scala-arm" % "1.4",
	"mysql" % "mysql-connector-java" % "5.1.36",
	"org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
	"org.slf4j" % "slf4j-api" % "1.7.12",
	"org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.3",
	"org.apache.logging.log4j" % "log4j-api" % "2.3",
	"org.apache.logging.log4j" % "log4j-core" % "2.3",
	"org.scala-lang" % "scala-reflect" % scalaVersion.value,
	"com.typesafe.akka" %% "akka-testkit" % "2.4.0" % "test",
	"org.scalatest" %% "scalatest" % "2.2.4" % "test",
	"org.bitcoinj" % "bitcoinj-core" % "0.13.1" % "test",
	"org.scala-lang.modules" %% "scala-pickling" % "0.10.1" % "test"
	)

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0-M5" cross CrossVersion.full)

lazy val macros = Project("bitcoin-akka-tutorial-macros", file("macros"))
lazy val root = Project("bitcoin-akka-tutorial", file(".")).dependsOn(macros)

test in assembly := {}

mainClass in assembly := Some("org.bitcoinakka.PeerManager")
