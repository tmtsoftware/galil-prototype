import sbt._

//noinspection TypeAnnotation
object Dependencies {

  val Version = "0.1-SNAPSHOT"
  val ScalaVersion = "2.12.3"
  val akkaVersion = "2.5.3"

  val `csw-params` = "org.tmt" %% "csw-params" % Version

  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3" // ApacheV2
  val `junit-interface` = "com.novocode" % "junit-interface" % "0.11"
}

