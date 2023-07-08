package csw.proto.galil.client

import java.net.InetAddress
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.util.Timeout
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.Completed
import csw.params.core.generics.KeyType
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.GalilAxisStatus

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * A demo client to test locating and communicating with the Galil HCD
 */
//noinspection ScalaWeakerAccess
object GalilHcdClientApp extends App {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "GalilHcdClientApp")
  implicit lazy val ec: ExecutionContextExecutor               = typedSystem.executionContext

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val galilHcdClient  = GalilHcdClient(Prefix("csw.galil.client"), locationService)
  private val maybeObsId      = None
  private val host            = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, typedSystem)
  implicit val timeout: Timeout = Timeout(3.seconds)
  private val log               = GenericLoggerFactory.getLogger
  log.info("Starting GalilHcdClientApp")

  val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
  println(s"setRelTarget: $resp1")
  val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
  println(s"getRelTarget: $resp2")

  val resp3 = Await.result(galilHcdClient.setBrushlessAxis(maybeObsId, 'A'), 3.seconds)
  println(s"setBrushlessAxis: $resp3")

  val resp4 = Await.result(galilHcdClient.setAnalogFeedbackSelect(maybeObsId, 'A', 6), 3.seconds)
  println(s"setAnalogFeedbackSelect: $resp4")

  val resp5 = Await.result(galilHcdClient.setBrushlessModulus(maybeObsId, 'A', 52000), 3.seconds)
  println(s"setBrushlessModulus: $resp5")

  val resp6 = Await.result(galilHcdClient.brushlessZero(maybeObsId, 'A', 1.0), 3.seconds)
  println(s"brushlessZero: $resp6")

  val resp7 = Await.result(galilHcdClient.motorOn(maybeObsId, 'A'), 3.seconds)
  println(s"motorOn: $resp7")

  val resp8 = Await.result(galilHcdClient.motorOff(maybeObsId, 'A'), 3.seconds)
  println(s"motorOff: $resp8")

  val resp9 = Await.result(galilHcdClient.setHomingMode(maybeObsId, 'A'), 3.seconds)
  println(s"setHomingMode: $resp9")

  val resp10 = Await.result(galilHcdClient.beginMotion(maybeObsId, 'A'), 3.seconds)
  println(s"beginMotion: $resp10")

  val resp11 = Await.result(galilHcdClient.setJogSpeed(maybeObsId, 'A', 166), 3.seconds)
  println(s"setJogSpeed: $resp11")

  val resp12 = Await.result(galilHcdClient.setFindIndexMode(maybeObsId, 'A'), 3.seconds)
  println(s"setFindIndexMode: $resp12")

  // --- Data Record Access ---

  // 1. getDataRecord sends QR and returns the parsed fields in the result (The keys are defined in the DataRecord object)
  val resp13 = Await.result(galilHcdClient.getDataRecord(maybeObsId), 3.seconds)

  println(s"getDataRecord: $resp13")
  val result = resp13.asInstanceOf[Completed].result

  // Example of how you could extract the motor position for each axis.
  // The axis status parameters for each axis are stored in params with the key name axis-$axis-$paramName
  // (For example, "axis-A-motorPosition").
  val blocksPresent = result.get(KeyType.CharKey.make("blocksPresent")).get.values
  blocksPresent.filter(b => b >= 'A' && b <= 'F').foreach { axis =>
    implicit val a: Char = axis
    val motorPos         = result.get(GalilAxisStatus.motorPositionKey)
    println(s"Axis $axis: motor position: $motorPos")
  }

  // 2. Alternative getDataRecordRaw command returns the raw bytes, so you can create a DataRecord object locally
  val dataRecord = Await.result(galilHcdClient.getDataRecordRaw(maybeObsId), 3.seconds)
  println(s"getDataRecordRaw: $dataRecord")
  // blocksPresent is a list that contains the name of the axis ("A" to "H") for each block present, or an empty string if the block not present
  val blocksPresent2 = dataRecord.header.blocksPresent
  // print the motor position for each axis that is present
  DataRecord.axes.zip(dataRecord.axisStatuses).foreach { p =>
    if (blocksPresent2.contains(p._1))
      println(s"Axis ${p._1}: motor position: ${p._2.motorPosition}")
  }

  typedSystem.terminate()
  typedSystem.whenTerminated.onComplete(_ => System.exit(0))
}
