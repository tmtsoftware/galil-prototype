package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.ccs.commands.CommandResponse.{Completed, CompletedWithResult}
import csw.messages.params.models.Prefix
import csw.services.location.commons.ClusterAwareSettings
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{FunSuite, Ignore}

import scala.concurrent.Await
import scala.concurrent.duration._

// Note: Test assumes that location service (csw-cluster-seed), galil-hcd and galil-simulator are running
@Ignore
class GalilClientTest extends FunSuite {
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"))
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  private val system: ActorSystem = ClusterAwareSettings.system
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)

  test("Test ") {
    val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
    println(s"setRelTarget: $resp1")
    assert(resp1.isInstanceOf[Completed])

    val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
    println(s"getRelTarget: $resp2")
    assert(resp2.isInstanceOf[CompletedWithResult])
  }
}
