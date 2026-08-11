package csw.proto.galil.simulator

import java.net.{InetAddress, NetworkInterface}
import org.apache.pekko.{Done, actor}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, SpawnProtocol}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import org.apache.pekko.stream.scaladsl.{Flow, Framing, Source, Tcp}
import org.apache.pekko.util.{ByteString, Timeout}
import csw.logging.client.commons.PekkoTypedExtension.UserActorFactory
import csw.proto.galil.simulator.GalilSimulatorActor.*

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
 * Simulates a Galil controller
 *
 * @param host host to bind to listen for new client connections
 * @param port port to use to listen for new client connections
 */
case class GalilSimulator(host: String = "127.0.0.1", port: Int = 8888, debug: Boolean = false)(implicit typedSystem: ActorSystem[SpawnProtocol.Command]) {

  implicit val classicSystem: actor.ActorSystem = typedSystem.classicSystem
  implicit val mat: Materializer                 = Materializer(classicSystem)
  implicit lazy val ec: ExecutionContextExecutor = classicSystem.dispatcher
  implicit val timeout: Timeout = Timeout(3.seconds)

  // Keep track of current connections, needed to simulate TH command
  private var activeConnections: Set[IncomingConnection] = Set.empty
  
  // Helper for debug logging
  private def debugLog(msg: => String): Unit = if (debug) println(msg)

  private val connections: Source[IncomingConnection, Future[ServerBinding]] =
    Tcp().bind(host, port)

  // An actor that simulates the motor motion based on the setttings
  private val simulatorActor: ActorRef[GalilSimulatorCommand] =
    typedSystem.spawn(Behaviors.withTimers[GalilSimulatorCommand](GalilSimulatorActor.simulate(_)), "GalilSimulatorActor")

  // Handle tcp connections
  println(s"Starting Galil simulator on $host:$port...")
  private val bindingFuture = connections.runForeach { conn =>
    debugLog(s"Client connected from ${conn.remoteAddress}")
    activeConnections += conn
    conn.handleWith(parseLines(conn))
  }
  
  // Monitor stream for errors
  bindingFuture.onComplete {
    case Success(binding) =>
      debugLog(s"Simulator stream completed normally")
    case Failure(ex) =>
      println(s"ERROR: Simulator stream failed: ${ex.getMessage}")
      ex.printStackTrace()
      System.exit(1)
  }(ec)
  
  println(s"Simulator ready and accepting connections on $host:$port")

  // Parses the incoming lines and process the Galil commands
  private def parseLines(conn: IncomingConnection) =
    Flow[ByteString]
      .map { bytes =>
        debugLog(s"[SIMULATOR] Received ${bytes.length} bytes from ${conn.remoteAddress}: ${bytes.utf8String.take(50)}")
        bytes
      }
      .via(Framing.delimiter(ByteString("\r\n"), maximumFrameLength = 256, allowTruncation = true))
      .map { frame =>
        debugLog(s"[SIMULATOR] Framed line: '${frame.utf8String}'")
        frame
      }
      // handle multiple commands on a line separated by ";"
      // Note: empty commands are valid Galil protocol (returns prompt ":") - don't filter them out
      .mapConcat { frame =>
        val parts = frame.utf8String.split(";").map(_.trim)
        // If original frame was empty (just \r\n), keep one empty string for the prompt response
        if (parts.forall(_.isEmpty)) List(ByteString(""))
        else parts.filter(_.nonEmpty).map(ByteString(_)).toList
      }
      .mapAsync(1) { cmd =>
        processCommand(cmd, conn).map { response =>
          // Add small delay to simulate real Galil processing time
          // This ensures text responses are sent in separate TCP packets
          // Note: QR (binary) responses may need different handling
          Thread.sleep(10)
          debugLog(s"[SIMULATOR] Sending response: '${response.utf8String.take(50)}'")
          response
        }
      }
      .watchTermination() { (_, f) =>
        closeConnection(f, conn)
      }

  // Process the Galil command and return the reply
  private def processCommand(cmd: ByteString, conn: IncomingConnection): Future[ByteString] = {
    val cmdString = cmd.utf8String
    debugLog(s"[SIMULATOR] Received command: '$cmdString'")

    if (cmdString.startsWith("'"))
      Future.successful(GalilSimulatorActor.formatReply(None)) // ignore comment lines starting with with "'"
    else
      cmdString match {
        case "TH" =>
          Future.successful(GalilSimulatorActor.formatReply(thCmd(conn)))
        case _ => simulatorActor ? (Command(cmdString, _))
      }

  }

  // Receives a future indicating when the flow associated with a client connection completes.
  // Used to keep track of active connections for the TH command.
  private def closeConnection(f: Future[Done], conn: IncomingConnection): Unit = {
    f.onComplete {
      case Success(_) =>
        debugLog(s"Closing connection $conn")
        activeConnections -= conn
      case Failure(ex) =>
        println(s"Error for connection $conn: $ex")
        ex.printStackTrace()
        activeConnections -= conn
    }
  }

  // Simulate the TH command (prints network info)
  // For example:
  // CONTROLLER IP ADDRESS 192,168,2,2 ETHERNET ADDRESS 00-50-4C-20-31-32
  //  IHA TCP PORT 23 TO IP ADDRESS 192,168,2,1 PORT 49328
  //  IHB TCP PORT 23 TO IP ADDRESS 192,168,2,1 PORT 48974
  //  IHC AVAILABLE
  //    IHD AVAILABLE
  //    IHE AVAILABLE
  //    IHF AVAILABLE
  //    IHG AVAILABLE
  //    IHH AVAILABLE
  private def thCmd(conn: IncomingConnection): String = {
    val inetAddr        = InetAddress.getByName(host)
    val ipAddrWithComma = inetAddr.getHostAddress.replace('.', ',')
    val networkIf       = NetworkInterface.getByInetAddress(inetAddr)
    val macAddr = Try(networkIf.getHardwareAddress.toList.map("%02X" format _).mkString("-"))
      .getOrElse("none")
    val controllerIp =
      s"CONTROLLER IP ADDRESS $ipAddrWithComma ETHERNET ADDRESS $macAddr"
    val connInfo = activeConnections
      .zip('A' to 'H')
      .map { a =>
        val localPort = a._1.localAddress.getPort
        val addrWithComma =
          a._1.remoteAddress.getAddress.getHostAddress.replace('.', ',')
        val port = a._1.remoteAddress.getPort
        s"IH${a._2} TCP PORT $localPort TO IP ADDRESS $addrWithComma PORT $port"
      }
      .mkString("\n")

    s"$controllerIp\n$connInfo"
    // TODO: add the "IHH AVAILABLE..." parts...
  }
}