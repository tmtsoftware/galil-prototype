package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.commands.CommandResponse.CompletedWithResult
import csw.messages.params.generics.KeyType
import csw.messages.params.models.Prefix
import csw.proto.galil.io.DataRecord
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.LoggingSystemFactory

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * A demo client to test locating and communicating with the Galil HCD
  */
object GalilHcdClientApp extends App {

  private val system: ActorSystem = ClusterAwareSettings.system
  private val locationService = LocationServiceFactory.withSystem(system)
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), system, locationService)
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)

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

  // Example of how you could extract the motor position for each axis
  val blocksPresent = result.get(KeyType.StringKey.make("blocksPresent")).get.values
  blocksPresent.filter(b => b >= "A" && b <= "F").foreach { axis =>
    val struct = result.get(KeyType.StructKey.make(axis)).get.head
    val motorPos = struct.get(KeyType.IntKey.make("motorPosition")).get.head
    println(s"Axis $axis: motor position: $motorPos")
  }

  // 2. Alternative getDataRecordRaw command returns the raw bytes, so you can create a DataRecord object locally
  val dataRecord = Await.result(galilHcdClient.getDataRecordRaw(maybeObsId), 3.seconds)
  println(s"getDataRecordRaw: $dataRecord")
  val blocksPresent2 = dataRecord.header.blocksPresent
  DataRecord.axes.zip(dataRecord.axisStatuses).foreach { p =>
    if (blocksPresent2.contains(p._1.toString))
      println(s"Axis ${p._1}: motor position: ${p._2.motorPosition}")
  }
}

