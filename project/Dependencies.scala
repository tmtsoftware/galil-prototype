import sbt._

//noinspection TypeAnnotation
object Dependencies {

  val GalilHcd = Seq(
    CSW.`csw-framework`,
    Libs.`pekko-http`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`pekko-actor-testkit-typed` % Test 
//    Libs.`junit` % Test,
//    Libs.`junit-interface` % Test
  )

  val GalilAssembly = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
//    Libs.`junit` % Test,
//    Libs.`junit-interface` % Test
  )

  val IcsAssemblies = Seq(
    CSW.`csw-framework`,
    Libs.`pekko-http`,                     // VbdsImagePublisher: HTTP client for the VBDS transfer/admin routes
    CSW.`csw-testkit` % Test,
    Libs.`scalatest` % Test,
    Libs.`pekko-actor-testkit-typed` % Test
  )

  val GalilClient = Seq(
    CSW.`csw-framework`,
    CSW.`csw-testkit`,
    Libs.scalatest % Test
  )

  val GalilDeploy = Seq(
    CSW.`csw-framework`,
    Libs.scalatest % Test
  )

  val GalilSimulator = Seq(
    CSW.`csw-framework`,
    Libs.scalatest % Test,
    Libs.`pekko-actor-testkit-typed` % Test
  )

  val GalilRepl = Seq(
    CSW.`csw-framework`
  )

  val GalilIo = Seq(
    CSW.`csw-framework`,
    Libs.playJson,
    Libs.scalatest % Test
  )

  val GalilCommands = Seq(
    CSW.`csw-framework`,
    Libs.scalatest % Test
  )
}