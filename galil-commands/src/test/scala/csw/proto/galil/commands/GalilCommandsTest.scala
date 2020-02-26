package csw.proto.galil.commands

import java.net.InetAddress

import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.logging.client.scaladsl.LoggingSystemFactory
import csw.params.commands.CommandResponse.Completed
import csw.params.commands.{CommandName, Setup}
import csw.params.core.models.ObsId
import csw.prefix.models.Prefix
import csw.proto.galil.io.GalilIoTcp
import org.scalatest.{BeforeAndAfterAll, FunSuite}

import scala.concurrent.ExecutionContextExecutor

class GalilCommandsTest extends FunSuite with BeforeAndAfterAll {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "GalilCommandsTest")
  implicit lazy val ec: ExecutionContextExecutor = typedSystem.executionContext
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, typedSystem)
  val galilIo: GalilIoTcp = GalilIoTcp() // default params: "127.0.0.1", 8888

  test("Test basic usage") {
    val cmds = GalilCommands(galilIo)
    val obsId = ObsId("2023-Q22-4-33")
    val prefix = Prefix("wfos.blue.filter")

    val response1 = cmds.sendCommand(
      Setup(prefix, CommandName("setRelTarget"), Some(obsId))
        .add(DeviceCommands.axisKey.set('A'))
        .add(DeviceCommands.countsKey.set(2)))

    assert(response1.isInstanceOf[Completed])

    val response2 = cmds.sendCommand(
      Setup(prefix, CommandName("getRelTarget"), Some(obsId))
        .add(DeviceCommands.axisKey.set('A')))

    response2 match {
      case Completed(_, result) =>
        val setup = Setup(prefix, CommandName("filter"), Some(obsId), result.paramSet)
        val x = setup.get(DeviceCommands.countsKey).get.head
        assert(x == 2)
        println("Test passed")

      case _ => fail("Test failed")
    }
  }
}
