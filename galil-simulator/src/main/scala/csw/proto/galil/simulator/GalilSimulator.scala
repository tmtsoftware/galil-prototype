package csw.proto.galil.simulator

import java.net.{InetAddress, NetworkInterface}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * Simulates a Galil controller
  *
  * @param host host to bind to listen for new client connections
  * @param port port to use to listen for new client connections
  */
case class GalilSimulator(host: String = "127.0.0.1", port: Int = 8888)
                         (implicit system: ActorSystem, mat: ActorMaterializer) {
  import system.dispatcher

  // Keep track of current connections, needed to simulate TH command
  private var activeConnections: Set[IncomingConnection] = Set.empty

  val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(host, port)

  connections.runForeach { conn =>
    activeConnections += conn
    conn.handleWith(serverLogic(conn))
  }

  private def serverLogic(conn: IncomingConnection) = Flow[ByteString]
    // handle lines
    .via(Framing.delimiter(ByteString("\r"), maximumFrameLength = 256, allowTruncation = true))
    // handle multiple commands on a line separated by ";"
    .mapConcat(_.utf8String.split(";").map(ByteString(_)).toList)
    .map(processCommand(_, conn))
    .watchTermination() { (_, f) => closeConnection(f, conn) }

  // From the Galil doc:
  // 2) Sending a Command
  // Once a socket is established, the user will need to send a Galil command as a string to
  // the controller (via the opened socket) followed by a Carriage return (0x0D).
  // 3) Receiving a Response
  // "The controller will respond to that command with a string. The response of the
  //command depends on which command was sent. In general, if there is a
  //response expected such as the "TP" Tell Position command. The response will
  //be in the form of the expected value(s) followed by a Carriage return (0x0D), Line
  //Feed (0x0A), and a Colon (:). If the command was rejected, the response will be
  //just a question mark (?) and nothing else. If the command is not expected to
  //return a value, the response will be just the Colon (:)."
  private def formatReply(reply: Option[String], isError: Boolean = false): String = {
    if (isError) "?" else reply match {
      case Some(msg) => s"$msg\r\n:"
      case None => ":"
    }
  }
  private def formatReply(reply: String): String = formatReply(Some(reply))

  // Process the Galil command and return the reply
  private def processCommand(cmd: ByteString, conn: IncomingConnection): ByteString = {
    val cmdString = cmd.utf8String
    val reply = if (cmdString.startsWith("'")) formatReply(None) // comment with "'"
    else cmdString match {
      case "badcmd" => formatReply(None, isError = true)
      case "noreplycmd" => formatReply(None)

      case "NO" => formatReply(None) // no-op

      case "TH" => formatReply(thCmd(conn))

      case _ => formatReply(s"$cmdString!!!")
    }
    ByteString(reply)
  }

  // Receives a future indicating when the flow associated with a client connection completes.
  // Used to keep track of active connections for the TH command.
  private def closeConnection(f: Future[Done], conn: IncomingConnection): Unit = {
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
    val macAddr = Try(networkIf.getHardwareAddress.toList.map("%02X" format _).mkString("-")).getOrElse("none")
    val controllerIp = s"CONTROLLER IP ADDRESS $ipAddrWithComma ETHERNET ADDRESS $macAddr"
    val connInfo = activeConnections.zip('A' to 'H').map { a =>
      val localPort = a._1.localAddress.getPort
      val addrWithComma = a._1.remoteAddress.getAddress.getHostAddress.replace('.', ',')
      val port = a._1.remoteAddress.getPort
      s"IH${a._2} TCP PORT $localPort TO IP ADDRESS $addrWithComma PORT $port"
    }.mkString("\n")

    s"$controllerIp\n$connInfo"
    // TODO: add the "IHH AVAILABLE..." parts...
  }
}