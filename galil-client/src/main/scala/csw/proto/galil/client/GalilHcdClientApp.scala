package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.params.models.Prefix
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
  println(s"setFindIndexMode: $resp11")  
  
   
}

