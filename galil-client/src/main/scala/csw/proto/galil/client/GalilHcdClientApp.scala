package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.CompletedWithResult
import csw.params.core.generics.KeyType
import csw.params.core.models.Prefix
import csw.proto.galil.io.DataRecord

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * A demo client to test locating and communicating with the Galil HCD
  */
object GalilHcdClientApp extends App {

  implicit val system: ActorSystem = ActorSystemFactory.remote("TestAssemblyClient")
  import system.dispatcher

  implicit val mat: ActorMaterializer = ActorMaterializer()
  private val locationService = HttpLocationServiceFactory.makeLocalClient(system, mat)
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), system, locationService)
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)
  implicit val timeout: Timeout = Timeout(3.seconds)
  private val log = GenericLoggerFactory.getLogger
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
 
  val resp7= Await.result(galilHcdClient.motorOn(maybeObsId, 'A'), 3.seconds)
  println(s"motorOn: $resp7")  
  
  val resp8= Await.result(galilHcdClient.motorOff(maybeObsId, 'A'), 3.seconds)
  println(s"motorOff: $resp8")  
 
  val resp9= Await.result(galilHcdClient.setHomingMode(maybeObsId, 'A'), 3.seconds)
  println(s"setHomingMode: $resp9")  

  val resp10= Await.result(galilHcdClient.beginMotion(maybeObsId, 'A'), 3.seconds)
  println(s"beginMotion: $resp10")  

  val resp11= Await.result(galilHcdClient.setJogSpeed(maybeObsId, 'A', 166), 3.seconds)
  println(s"setJogSpeed: $resp11")  

  val resp12= Await.result(galilHcdClient.setFindIndexMode(maybeObsId, 'A'), 3.seconds)
  println(s"setFindIndexMode: $resp12")


  // --- Data Record Access ---

  // 1. getDataRecord sends QR and returns the parsed fields in the result (The keys are defined in the DataRecord object)
  val resp13= Await.result(galilHcdClient.getDataRecord(maybeObsId), 3.seconds)
  println(s"getDataRecord: $resp13")
  val result = resp13.asInstanceOf[CompletedWithResult].result

  // Example of how you could extract the motor position for each axis.
  // The axis status for each axis is stored in a param set "struct" with tha name of the axis.
  val blocksPresent = result.get(KeyType.StringKey.make("blocksPresent")).get.values
  blocksPresent.filter(b => b >= "A" && b <= "F").foreach { axis =>
    val struct = result.get(KeyType.StructKey.make(axis)).get.head
    val motorPos = struct.get(KeyType.IntKey.make("motorPosition")).get.head
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

  import system.dispatcher
  system.terminate().onComplete(_ => System.exit(0))
}

