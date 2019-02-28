import sbt._

object Libs {
  val `scopt` = "com.github.scopt" %% "scopt" % "3.7.0" //MIT License
  val `scalaTest` = "org.scalatest" %% "scalatest" % "3.0.5" // ApacheV2
  val `junit-interface` = "com.novocode" % "junit-interface" % "0.11"
  val `slf4j-api` = "org.slf4j" % "slf4j-api" % "1.7.25"
  val playJson = "com.typesafe.play" %% "play-json" % "2.6.5"
}

object CSW {
  private val Org = "com.github.tmtsoftware.csw"
//  private val Version = "v0.6.0" //change this to 0.1-SNAPSHOT to test with local csw changes (after publishLocal)
  private val Version = "0.1-SNAPSHOT"

  val `csw-framework` = Org %% "csw-framework" % Version
  val `csw-config-client` = Org %% "csw-config-client" % Version
  val `csw-aas-native` = Org %% "csw-aas-native" % Version
  val `csw-location-client` = Org %% "csw-location-client" % Version

}
