import java.io.FileReader
import java.util.Properties

import sbt._

import scala.util.control.NonFatal

object Libs {
  val ScalaVersion = "2.13.8"

  val `scopt` = "com.github.scopt" %% "scopt" % "4.0.1" //MIT License
  val `slf4j-api` = "org.slf4j" % "slf4j-api" % "1.7.25"
  val playJson = "com.typesafe.play" %% "play-json" % "2.9.2"

  val `scalatest`       = "org.scalatest"          %% "scalatest"       % "3.2.11"  //Apache License 2.0
  val `scala-async`     = "org.scala-lang.modules" %% "scala-async"     % "1.0.1"  //BSD 3-clause "New" or "Revised" License
//  val `junit`           = "junit"                  %  "junit"           % "4.13.1"  //Eclipse Public License 1.0
//  val `junit-interface` = "com.novocode"           %  "junit-interface" % "0.13.2"   //BSD 2-clause "Simplified" License
  val `mockito-scala`   = "org.mockito"            %% "mockito-scala"   % "1.17.12"
}

object CSW {

  // If you want to change CSW version, then update "csw.version" property in "build.properties" file
  // Same "csw.version" property is used in "scripts/csw-services" script,
  // this makes sure that CSW library dependency and CSW services version is in sync
  val Version: String = {
    var reader: FileReader = null
    try {
      val properties = new Properties()
      reader = new FileReader("project/build.properties")
      properties.load(reader)
      val version = properties.getProperty("csw.version")
      println(s"[info]] Using CSW version [$version] ***********")
      version
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
        throw e
    } finally reader.close()
  }

  val `csw-framework` = "com.github.tmtsoftware.csw" %% "csw-framework" % Version
  val `csw-testkit`   = "com.github.tmtsoftware.csw" %% "csw-testkit" % Version
}
