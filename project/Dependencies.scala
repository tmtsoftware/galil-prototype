import sbt._

object Dependencies {

  val `galil-hcd-deps` = Seq(
    CSW.`csw-framework`
  )

  val `galil-assembly-deps` = Seq(
    CSW.`csw-framework`
  )

  val `galil-client-deps` = Seq(
    CSW.`csw-framework`,
    Libs.scalaTest % Test
  )

  val `galil-simulator-deps` = Seq(
    CSW.`csw-framework`
  )

  val `galil-repl-deps` = Seq(
    CSW.`csw-framework`,
    CSW.`csw-config-client`,
    CSW.`csw-aas-native`,
    CSW.`csw-location-client`
  )

  val `galil-io-deps` = Seq(
    CSW.`csw-framework`,
    Libs.playJson,
    Libs.scalaTest % Test
  )

  val `galil-commands-deps` = Seq(
    CSW.`csw-framework`,
    Libs.scalaTest % Test
  )

  val `galil-deploy-deps` = Seq(
    CSW.`csw-framework`
  )
}
