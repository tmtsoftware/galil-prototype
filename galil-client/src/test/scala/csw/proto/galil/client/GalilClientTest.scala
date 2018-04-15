package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.commands.CommandResponse.{Completed, CompletedWithResult}
import csw.messages.params.generics.KeyType
import csw.messages.params.models.{Prefix, Struct}
import csw.proto.galil.client.GalilHcdClientApp.result
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.LocationServiceFactory
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{FunSuite, Ignore}

import scala.concurrent.Await
import scala.concurrent.duration._

// Note: Test assumes that location service (csw-cluster-seed), galil-hcd and galil-simulator are running
//@Ignore
class GalilClientTest extends FunSuite {
  private val system: ActorSystem = ClusterAwareSettings.system
  private val locationService = LocationServiceFactory.withSystem(system)
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), system, locationService)
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, system)

  test("Test ") {
    val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
    println(s"setRelTarget: $resp1")
    assert(resp1.isInstanceOf[Completed])

    val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
    println(s"getRelTarget: $resp2")
    assert(resp2.isInstanceOf[CompletedWithResult])

    val resp3 = Await.result(galilHcdClient.getDataRecord(maybeObsId), 3.seconds)
    println(s"getDataRecord: $resp3")
    assert(resp3.isInstanceOf[CompletedWithResult])

    val result = resp3.asInstanceOf[CompletedWithResult].result

    // Example of how you could extract the motor position for each axis
    val blocksPresent = result.get(KeyType.StringKey.make("blocksPresent")).get.values
    blocksPresent.filter(b => b >= "A" && b <= "F").foreach { axis =>
      val struct = result.get(KeyType.StructKey.make(axis)).get.head
      val motorPos = struct.get(KeyType.IntKey.make("motorPosition")).get.head
      println(s"Axis $axis: motor position: $motorPos")
    }
  }
}
