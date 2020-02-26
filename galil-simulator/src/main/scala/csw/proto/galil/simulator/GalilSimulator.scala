package csw.proto.galil.simulator

import java.net.{InetAddress, NetworkInterface}

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, SpawnProtocol}
import akka.stream.Materializer
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.scaladsl.{Flow, Framing, Source, Tcp}
import akka.util.{ByteString, Timeout}
import csw.logging.client.commons.AkkaTypedExtension.UserActorFactory
import csw.proto.galil.simulator.GalilSimulatorActor._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/**
  * Simulates a Galil controller
  *
  * @param host host to bind to listen for new client connections
  * @param port port to use to listen for new client connections
  */
case class GalilSimulator(host: String = "127.0.0.1", port: Int = 8888)(implicit system: ActorSystem) {

  implicit val mat: Materializer = Materializer(system)
  implicit lazy val ec: ExecutionContextExecutor = system.dispatcher
  implicit lazy val typedSystem: akka.actor.typed.ActorSystem[SpawnProtocol.Command] = akka.actor.typed.ActorSystem(SpawnProtocol(), "typed-system")
  implicit val timeout: Timeout = Timeout(3.seconds)

  // Keep track of current connections, needed to simulate TH command
  private var activeConnections: Set[IncomingConnection] = Set.empty

  private val connections: Source[IncomingConnection, Future[ServerBinding]] =
    Tcp().bind(host, port)

  // An actor that simulates the motor motion based on the setttings
  private val simulatorActor: ActorRef[GalilSimulatorCommand] =
    typedSystem.spawn(Behaviors.withTimers[GalilSimulatorCommand](GalilSimulatorActor.simulate(_)), "GalilSimulatorActor")

  // Handle tcp connections
  connections.runForeach { conn =>
    activeConnections += conn
    conn.handleWith(parseLines(conn))
  }

  // Parses the incomming lines and process the Galil commands
  private def parseLines(conn: IncomingConnection) =
    Flow[ByteString]
      .via(
        Framing.delimiter(ByteString("\r\n"),
          maximumFrameLength = 256,
          allowTruncation = true))
      // handle multiple commands on a line separated by ";"
      .mapConcat(_.utf8String.split(";").map(ByteString(_)).toList)
      .mapAsync(1)(processCommand(_, conn))
      .watchTermination() { (_, f) =>
        closeConnection(f, conn)
      }

  // Process the Galil command and return the reply
  private def processCommand(cmd: ByteString,
                             conn: IncomingConnection): Future[ByteString] = {
    val cmdString = cmd.utf8String
    println(cmdString)

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
  private def closeConnection(f: Future[Done],
                              conn: IncomingConnection): Unit = {
    f.onComplete {
      case Success(_) =>
        println(s"Closing connection $conn")
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
    val inetAddr = InetAddress.getByName(host)
    val ipAddrWithComma = inetAddr.getHostAddress.replace('.', ',')
    val networkIf = NetworkInterface.getByInetAddress(inetAddr)
    val macAddr = Try(
      networkIf.getHardwareAddress.toList.map("%02X" format _).mkString("-"))
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
