package csw.proto.galil.commands

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.ccs.commands.CommandResponse.{Completed, CompletedWithResult}
import csw.messages.ccs.commands.Setup
import csw.messages.params.models.{ObsId, Prefix}
import csw.proto.galil.io.GalilIoTcp
import csw.services.location.commons.ActorSystemFactory
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{BeforeAndAfterAll, FunSuite}

class GalilCommandsTest extends FunSuite with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystemFactory.remote
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, system)
  val galilIo = GalilIoTcp() // default params: "127.0.0.1", 8888

  test("Test basic usage") {
    val cmds = GalilCommands(galilIo)
    val obsId = ObsId("2023-Q22-4-33")
    val prefix = Prefix("wfos.blue.filter")

    val response1 = cmds.sendCommand(
      Setup(prefix, prefix, Some(obsId))
        .add(DeviceCommands.commandKey.set("setRelTarget"))
        .add(DeviceCommands.axisKey.set('A'))
        .add(DeviceCommands.countsKey.set(2)))

    assert(response1.isInstanceOf[Completed])

    val response2 = cmds.sendCommand(
      Setup(prefix, prefix, Some(obsId))
        .add(DeviceCommands.commandKey.set("getRelTarget"))
        .add(DeviceCommands.axisKey.set('A')))

    response2 match {
      case CompletedWithResult(_, result) =>
        val setup = Setup(prefix, prefix, Some(obsId), result.paramSet)
        val x = setup.get(DeviceCommands.countsKey).get.head
        assert(x == 2)
        println("Test passed")

      case _ => fail("Test failed")
    }
  }
}
