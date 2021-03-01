package csw.proto.galil.client

import java.net.InetAddress
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import akka.util.Timeout
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.Completed
import csw.params.core.generics.KeyType
import csw.prefix.models.Prefix
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

// Note: Test assumes that location service, galil-hcd and galil-simulator are running
//@Ignore
class GalilClientTest extends AnyFunSuite {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "GalilClientTest")
  implicit lazy val ec: ExecutionContextExecutor               = typedSystem.executionContext
  implicit val timeout: Timeout                                = Timeout(3.seconds)

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val galilHcdClient  = GalilHcdClient(Prefix("csw.galil.client"), locationService)
  private val maybeObsId      = None
  private val host            = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilClientTest", "0.1", host, typedSystem)
  private val log = GenericLoggerFactory.getLogger
  log.info("Starting GalilClientTest")

  test("Test ") {
    val resp1 = Await.result(galilHcdClient.setRelTarget(maybeObsId, 'A', 3), 3.seconds)
    println(s"setRelTarget: $resp1")
    assert(resp1.isInstanceOf[Completed])

    val resp2 = Await.result(galilHcdClient.getRelTarget(maybeObsId, 'A'), 3.seconds)
    println(s"getRelTarget: $resp2")
    assert(resp2.isInstanceOf[Completed])

    val resp3 = Await.result(galilHcdClient.getDataRecord(maybeObsId), 3.seconds)
    println(s"getDataRecord: $resp3")
    assert(resp3.isInstanceOf[Completed])

    val result = resp3.asInstanceOf[Completed].result

    // Example of how you could extract the motor position for each axis
    val blocksPresent = result.get(KeyType.CharKey.make("blocksPresent")).get.values
    blocksPresent.filter(b => b >= 'A' && b <= 'F').foreach { axis =>
      val struct   = result.get(KeyType.StructKey.make(axis.toString)).get.head
      val motorPos = struct.get(KeyType.IntKey.make("motorPosition")).get.head
      println(s"Axis $axis: motor position: $motorPos")
    }
  }
}
