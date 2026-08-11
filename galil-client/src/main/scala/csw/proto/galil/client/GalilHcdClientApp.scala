package csw.proto.galil.client

import java.net.InetAddress
import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.Timeout
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.{GenericLoggerFactory, LoggingSystemFactory}
import csw.params.commands.CommandResponse.Completed
import csw.params.core.generics.KeyType
import csw.prefix.models.Prefix
import csw.proto.galil.io.DataRecord

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * A demo client to test the updated Galil HCD with embedded program execution interface
 * 
 * Prerequisites:
 * 1. CSW services running (csw-services start)
 * 2. Galil HCD running with RegisterOnly
 * 3. Embedded program "MoveA" loaded on Galil controller (or use different program name)
 */
//noinspection ScalaWeakerAccess
object GalilHcdClientApp {
  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "GalilHcdClientApp")
  implicit lazy val ec: ExecutionContextExecutor               = typedSystem.executionContext

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val galilHcdClient  = GalilHcdClient(Prefix("CSW.galil.client"), locationService)
  private val maybeObsId      = None
  private val host            = InetAddress.getLocalHost.getHostName
  LoggingSystemFactory.start("GalilHcdClientApp", "0.1", host, typedSystem)
  implicit val timeout: Timeout = Timeout(10.seconds)
  private val log               = GenericLoggerFactory.getLogger

  def main(args: Array[String]): Unit = {

    log.info("Starting GalilHcdClientApp - Testing New Interface")
    println("\n=== Testing New Galil HCD Interface ===\n")

    // ===== Variable Setting (using convenience methods) =====
    
    println("--- Testing Variable Operations ---")
    
    val resp1 = Await.result(galilHcdClient.setVariable("posn[0]", 0.0, maybeObsId), 10.seconds)
    println(s"1. setVariable posn[0]=0: $resp1")
    
    val resp2 = Await.result(galilHcdClient.getVariable("posn[0]", maybeObsId), 10.seconds)
    println(s"2. getVariable posn[0]: $resp2")
    
    val resp3 = Await.result(galilHcdClient.setVariable("posn[0]", 100.0, maybeObsId), 10.seconds)
    println(s"3. setVariable posn[0]=100: $resp3")
    
    val resp4 = Await.result(galilHcdClient.getVariable("posn[0]", maybeObsId), 10.seconds)
    println(s"4. getVariable posn[0]: $resp4")
    
    // ===== Program Execution =====
    
    println("\n--- Testing Program Execution ---")
    
    val resp5 = Await.result(galilHcdClient.executeProgram("MoveA", 1, maybeObsId), 10.seconds)
    println(s"5. executeProgram MoveA on thread 1: $resp5")
    
    // Give program time to execute
    Thread.sleep(2000)
    
    val resp6 = Await.result(galilHcdClient.haltExecution(1, maybeObsId), 10.seconds)
    println(s"6. haltExecution thread 1: $resp6")
    
    // ===== Digital I/O =====
    
    println("\n--- Testing Digital I/O ---")
    
    val resp7 = Await.result(galilHcdClient.setBit(1, maybeObsId), 10.seconds)
    println(s"7. setBit address 1: $resp7")
    
    val resp8 = Await.result(galilHcdClient.clearBit(1, maybeObsId), 10.seconds)
    println(s"8. clearBit address 1: $resp8")
    
    // ===== Analog I/O =====
    
    println("\n--- Testing Analog I/O ---")
    
    val resp9 = Await.result(galilHcdClient.readAnalogInput(0, maybeObsId), 10.seconds)
    println(s"9. readAnalogInput channel 0: $resp9")
    
    val resp10 = Await.result(galilHcdClient.writeAnalogOutput(1, 2.5, maybeObsId), 10.seconds)
    println(s"10. writeAnalogOutput channel 1, value 2.5V: $resp10")
    
    // ===== Generic Command =====
    
    println("\n--- Testing Generic sendCommand ---")
    
    val resp11 = Await.result(galilHcdClient.sendCommand("posn[0]=200", maybeObsId), 10.seconds)
    println(s"11. sendCommand 'posn[0]=200': $resp11")
    
    val resp12 = Await.result(galilHcdClient.sendCommand("MG posn[0]", maybeObsId), 10.seconds)
    println(s"12. sendCommand 'MG posn[0]': $resp12")
    
    val resp13 = Await.result(galilHcdClient.sendCommand("MG _TDA", maybeObsId), 10.seconds)
    println(s"13. sendCommand 'MG _TDA' (axis A position): $resp13")
    
    // ===== Data Record Access =====
    
    println("\n--- Testing Data Record ---")

    // 1. getDataRecord sends QR and returns the parsed fields in the result
    val resp14 = Await.result(galilHcdClient.getDataRecord(maybeObsId), 10.seconds)
    println(s"14. getDataRecord: $resp14")
    
    val result = resp14.asInstanceOf[Completed].result

    // Example of how you could extract the motor position for each axis
    val blocksPresent = result.get(KeyType.CharKey.make("blocksPresent")).get.values
    println(s"    Blocks present: ${blocksPresent.mkString(", ")}")
    
    blocksPresent.filter(b => b >= 'A' && b <= 'F').foreach { axis =>
      implicit val a: Char = axis
      val motorPos         = result.get(DataRecord.GalilAxisStatus.motorPositionKey)
      println(s"    Axis $axis motor position: $motorPos")
    }

    // 2. Alternative getDataRecordRaw command returns the raw bytes
    val dataRecord = Await.result(galilHcdClient.getDataRecordRaw(maybeObsId), 10.seconds)
    println(s"15. getDataRecordRaw: DataRecord with ${dataRecord.header.recordSize} bytes")
    
    val blocksPresent2 = dataRecord.header.blocksPresent
    println(s"    Blocks present: ${blocksPresent2.mkString(", ")}")
    
    // Print motor position for each axis that is present
    DataRecord.axes.zip(dataRecord.axisStatuses).foreach { p =>
      if (blocksPresent2.contains(p._1))
        println(s"    Axis ${p._1} motor position: ${p._2.motorPosition}")
    }
    
    println("\n=== All Tests Complete ===\n")

    typedSystem.terminate()
    typedSystem.whenTerminated.onComplete(_ => System.exit(0))
  }
}
