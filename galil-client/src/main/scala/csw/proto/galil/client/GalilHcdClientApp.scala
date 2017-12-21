package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.params.models.Prefix
import csw.services.location.commons.ClusterAwareSettings
import csw.services.logging.scaladsl.LoggingSystemFactory

import scala.concurrent.Await
import scala.concurrent.duration._

// A client to test locating and communicating with the Galil HCD
object GalilHcdClientApp extends App {

  private val system: ActorSystem = ClusterAwareSettings.system
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), system)
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)

  val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
  println(s"setRelTarget: $resp1")
  val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
  println(s"getRelTarget: $resp2")
}

