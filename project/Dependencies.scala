import sbt._

//noinspection TypeAnnotation
object Dependencies {

  val Version = "0.1-SNAPSHOT"
  val CswVersion = "0.1-SNAPSHOT"
  val ScalaVersion = "2.12.6"
  val PlayVersion = "2.6.5"

  val `csw-framework` = "org.tmt" %% "csw-framework" % Version

  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5" // ApacheV2
  val `junit-interface` = "com.novocode" % "junit-interface" % "0.11"

  val `slf4j-api` = "org.slf4j" % "slf4j-api" % "1.7.25"
  val playJson = "com.typesafe.play" %% "play-json" % PlayVersion
}

