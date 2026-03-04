import java.io.FileReader
import java.util.Properties

import sbt._

import scala.util.control.NonFatal

object Libs {
  val ScalaVersion: String = "3.6.4"

  val `scopt` = "com.github.scopt" %% "scopt" % "4.1.0" //MIT License
  val `slf4j-api` = "org.slf4j" % "slf4j-api" % "2.0.17"
  val playJson = "com.typesafe.play" %% "play-json" % "2.10.6"

  val `scalatest`       = "org.scalatest"          %% "scalatest"       % "3.2.19"  //Apache License 2.0
  val `dotty-cps-async` = "com.github.rssh" %% "dotty-cps-async" % "0.9.23"
  val `pekko-actor-testkit-typed` = "org.apache.pekko" %% "pekko-actor-testkit-typed" % "1.1.3"
  val `pekko-http`               = "org.apache.pekko" %% "pekko-http" % "1.1.0"
//  val `junit`           = "junit"                  %  "junit"           % "4.13.1"  //Eclipse Public License 1.0
//  val `junit-interface` = "com.novocode"           %  "junit-interface" % "0.13.2"   //BSD 2-clause "Simplified" License
  val `mockito-scala`   = "org.mockito"            %% "mockito-scala"   % "2.0.0"
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