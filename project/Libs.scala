import sbt._

object Libs {
  val `scopt` = "com.github.scopt" %% "scopt" % "3.7.1" //MIT License
  val `scalaTest` = "org.scalatest" %% "scalatest" % "3.0.8" // ApacheV2
  val `junit-interface` = "com.novocode" % "junit-interface" % "0.11"
  val `slf4j-api` = "org.slf4j" % "slf4j-api" % "1.7.25"
  val playJson = "com.typesafe.play" %% "play-json" % "2.7.4"
}

object CSW {
  private val Org = "com.github.tmtsoftware.csw"
//  private val Version = "0.7.0-RC1"
//  private val Version = "0.1-SNAPSHOT"
  private val Version = "1.0.0-RC2"

  val `csw-framework` = Org %% "csw-framework" % Version
}
