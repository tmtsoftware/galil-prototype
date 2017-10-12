package csw.proto.galil.commands

import java.net.InetAddress

import akka.actor.ActorSystem
import csw.messages.{CommandResponse, CompletedWithResult}
import csw.messages.ccs.commands.{CommandInfo, Setup}
import csw.messages.params.generics.Parameter
import csw.messages.params.models.{ObsId, Prefix, RunId}
import csw.proto.galil.io.GalilIoTcp
import csw.services.location.scaladsl.ActorSystemFactory
import csw.services.logging.scaladsl.LoggingSystemFactory
import org.scalatest.{BeforeAndAfterAll, FunSuite}

class GalilCommandsTest extends FunSuite with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystemFactory.remote
  private val localHost = InetAddress.getLocalHost.getHostName

  LoggingSystemFactory.start("GalilIoTests", "0.1", localHost, system)
  val galilIo = GalilIoTcp() // default params: "127.0.0.1", 8888

  test("Test basic usage") {
    val cmds = GalilCommands(galilIo)
    val cmdInfo = CommandInfo(ObsId("Obs001"), RunId())
    val prefix = Prefix("wfos.blue.filter")

    val response1 = cmds.sendCommand(
      Setup(cmdInfo, prefix)
        .add(DeviceCommands.commandKey.set("setRelTarget"))
        .add(DeviceCommands.axisKey.set('A'))
        .add(DeviceCommands.countsKey.set(2)))

    val response2 = cmds.sendCommand(
      Setup(cmdInfo, prefix)
        .add(DeviceCommands.commandKey.set("getRelTarget"))
        .add(DeviceCommands.axisKey.set('A')))

    response2 match {
      case CompletedWithResult(result) =>
        val setup = Setup(cmdInfo, prefix, result.paramSet)
        val x = setup.get(DeviceCommands.countsKey).get.head
        assert(x == 2)
        println("Test passed")

      case x => fail("Test failed")
    }
  }
}
