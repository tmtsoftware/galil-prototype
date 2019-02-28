package csw.proto.galil.client

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import csw.location.client.ActorSystemFactory
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.{Completed, CompletedWithResult}
import csw.params.core.generics.KeyType
import csw.params.core.models.Prefix
import org.scalatest.FunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

// Note: Test assumes that location service, galil-hcd and galil-simulator are running
//@Ignore
class GalilClientTest extends FunSuite {
  implicit val system: ActorSystem = ActorSystemFactory.remote("TestAssemblyClient")
  import system.dispatcher

  implicit val mat: ActorMaterializer = ActorMaterializer()
  private val locationService = HttpLocationServiceFactory.makeLocalClient(system, mat)
  private val galilHcdClient = GalilHcdClient(Prefix("test.galil.client"), system, locationService)
  private val maybeObsId = None
  private val host = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilClientTest", "0.1", host, system)
  implicit val timeout: Timeout = Timeout(3.seconds)
  private val log = GenericLoggerFactory.getLogger
  log.info("Starting GalilClientTest")

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
