import sbt._

//noinspection TypeAnnotation
object Dependencies {

  val Version = "0.1-SNAPSHOT"
  val ScalaVersion = "2.12.4"
  val PlayVersion = "2.6.5"

  val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3" // ApacheV2
  val `junit-interface` = "com.novocode" % "junit-interface" % "0.11"
  val playJson = "com.typesafe.play" %% "play-json" % PlayVersion

  val `csw-framework`  = "org.tmt" %% "csw-framework"  % Version
  val `galil-assembly-dep` = "org.tmt" %% "galil-assembly" % Version
  val `galil-hcd-dep`      = "org.tmt" %% "galil-hcd"      % Version
}

