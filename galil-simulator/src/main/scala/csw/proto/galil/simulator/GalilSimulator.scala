package csw.proto.galil.simulator

import java.net.{InetAddress, NetworkInterface}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.ByteString
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord._

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

  private val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(host, port)

  // For TC command
  private var errorStatus = 0
  private val errorMessage = "Unrecognized command"

  // Saves current axis values for commands as Map of command -> (map of axis -> value)
  private var cmdMap: Map[String, Map[Char, Double]] = Map(
    "DP" -> Map.empty,
    "JG" -> Map.empty,
    "KS" -> Map.empty,
    "LC" -> Map.empty,
    "MT" -> Map.empty,
    "PA" -> Map.empty,
    "PR" -> Map.empty,
    "RP" -> Map.empty,
    "SP" -> Map.empty,
    "AF" -> Map.empty,
    "BM" -> Map.empty,
  )

  connections.runForeach { conn =>
    activeConnections += conn
    conn.handleWith(serverLogic(conn))
  }

  private def serverLogic(conn: IncomingConnection) = Flow[ByteString]
    // handle lines
    .via(Framing.delimiter(ByteString("\r\n"), maximumFrameLength = 256, allowTruncation = true))
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
    errorStatus = if (isError) 1 else 0
    if (isError) "?" else reply match {
      case Some(msg) => s"$msg\r\n:"
      case None => ":"
    }
  }

  private def formatReply(reply: String): String = formatReply(Some(reply))

  // Process the Galil command and return the reply
  private def processCommand(cmd: ByteString, conn: IncomingConnection): ByteString = {
    val cmdString = cmd.utf8String
    
    println(cmdString);
    
    if (cmdString == "QR") {
        ByteString(getDataRecord.toByteBuffer)
    } else {
      val reply = if (cmdString.startsWith("'"))
        formatReply(None) // comment with "'"
      else try {
        val cmd = cmdString.take(2)
        if (cmdMap.contains(cmd)) formatReply(genericCmd(cmdString))
        else cmd match { // basic commands are two upper case chars
          case "" => formatReply(None) // pressing return will return a ":"
          case "BG" => formatReply(None)
          case "MO" => formatReply(None)
          case "NO" => formatReply(None) // no-op
          case "SH" => formatReply(None)
          case "ST" => formatReply(None)
          case "BA" => formatReply(None)
          case "BZ" => formatReply(None)
          case "HM" => formatReply(None)
          case "FI" => formatReply(None)
          case "TC" => formatReply(tcCmd(cmdString))
          case "TH" => formatReply(thCmd(conn))
          case "TS" => formatReply(None)
          case _ => formatReply(None, isError = true)
        }
      } catch {
        case ex: Throwable =>
          ex.printStackTrace()
          formatReply(None)
      }
      ByteString(reply)
    }
  }

  private def getDataRecord: DataRecord = {
    // XXX dummy values
    val blocksPresent = List("A")
//    val blocksPresent = List("S", "T", "I", "A", "B", "C", "D", "E", "F", "G", "H")

    val recordSize = 200
    val header = Header(blocksPresent, recordSize)

    val sampleNumber = 0
    val inputs = Array(0.toByte)
    val outputs = Array(0.toByte)
    val ethernetHandleStatus = Array(0.toByte)
    val errorCode = 0.toByte
    val threadStatus = 0.toByte
    val amplifierStatus = 0
    val contourModeSegmentCount = 0
    val contourModeBufferSpaceRemaining = 0
    val sPlaneSegmentCount = 0
    val sPlaneMoveStatus = 0
    val sPlaneDistanceTraveled = 0
    val sPlaneBufferSpaceRemaining = 0
    val tPlaneSegmentCount = 0
    val tPlaneMoveStatus = 0
    val tPlaneDistanceTraveled = 0
    val tPlaneBufferSpaceRemaining = 0

    val generalState = GeneralState(sampleNumber, inputs, outputs, ethernetHandleStatus, errorCode,
      threadStatus, amplifierStatus, contourModeSegmentCount, contourModeBufferSpaceRemaining,
      sPlaneSegmentCount, sPlaneMoveStatus, sPlaneDistanceTraveled, sPlaneBufferSpaceRemaining,
      tPlaneSegmentCount, tPlaneMoveStatus, tPlaneDistanceTraveled, tPlaneBufferSpaceRemaining)

    val axisStatuses = Array(GalilAxisStatus())

    DataRecord(header, generalState, axisStatuses)
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


  // Simulates commands that let you set and get values, for example:
  //
  // PR[A-z]=?
  //  setRelTarget: {
  //    command: "PR(axis)=(counts)"
  //    responseFormat: ""
  //  }
  //  getRelTarget: {
  //    command: "PR(axis)=?"
  //    responseFormat: ".*?(counts)"
  //  }
  //
  // TODO: Support other variations of the syntax, such as where counts is ",,n,n,...".
  //
  // Command is the first two chars, axis should be the third.
  private def genericCmd(cmdString: String): String = {
    val cmd = cmdString.take(2)
    val map = cmdMap(cmd)
    val axis = cmdString.drop(2).head
    val value = cmdString.drop(4)
    value match {
      case "?" =>
        map(axis).toString
      case _ =>
        val newMap = map + (axis -> value.toDouble)
        cmdMap = cmdMap + (cmd -> newMap)
        ""
    }
  }

  // Simulates the TC command:
  private def tcCmd(cmdString: String): String = {
    val n = cmdString.drop(2)
    if (n == "0")
      s"$errorStatus"
    else if (errorStatus == 0)
      s"$errorStatus"
    else s"$errorStatus $errorMessage"
  }
}
