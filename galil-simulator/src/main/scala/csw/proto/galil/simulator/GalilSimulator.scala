package csw.proto.galil.simulator

import java.net.{InetAddress, NetworkInterface}

import akka.Done
import akka.actor.{ActorSystem, Scheduler}
import akka.stream.ActorMaterializer
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.stream.scaladsl.{Flow, Framing, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.{ByteString, Timeout}
import csw.proto.galil.io.DataRecord

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import GalilSimulatorActor._
import akka.actor.typed.scaladsl.AskPattern._
import scala.concurrent.duration._

/**
  * Simulates a Galil controller
  *
  * @param host host to bind to listen for new client connections
  * @param port port to use to listen for new client connections
  */
case class GalilSimulator(host: String = "127.0.0.1", port: Int = 8888)(
    implicit system: ActorSystem,
    mat: ActorMaterializer) {

  import system.dispatcher
  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit val sched: Scheduler = system.scheduler


  // Keep track of current connections, needed to simulate TH command
  private var activeConnections: Set[IncomingConnection] = Set.empty

  private val connections: Source[IncomingConnection, Future[ServerBinding]] =
    Tcp().bind(host, port)

  // For TC command
  private var errorStatus = 0
  private val errorMessage = "Unrecognized command"

  // Some commands that set a value for an axis (XXX TODO: Add to this list)
  private val axixCmds = Array("AC",
                               "AF",
                               "AG",
                               "BM",
                               "BZ",
                               "DC",
                               "DP",
                               "JG",
                               "KS",
                               "LC",
                               "MT",
                               "PA",
                               "PR",
                               "PT",
                               "RP",
                               "SP",
                               "YA",
                               "YB")

  // Saves current axis values for commands as Map of command -> (map of axis -> value)
  private var cmdMap: Map[String, Map[Char, Double]] =
    axixCmds.map(_ -> Map.empty[Char, Double]).toMap

  // An actor that simulates the motor motion based on the setttings
  private val simulatorActor =
    system.spawn(GalilSimulatorActor.simulate(), "GalilSimulatorActor")

  // Handle tcp connections
  connections.runForeach { conn =>
    activeConnections += conn
    conn.handleWith(serverLogic(conn))
  }

  private def serverLogic(conn: IncomingConnection) =
    Flow[ByteString]
    // handle lines
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
  private def formatReply(reply: Option[String],
                          isError: Boolean = false): Future[String] = {
    errorStatus = if (isError) 1 else 0
    if (isError) "?"
    else
      reply match {
        case Some(msg) => s"$msg\r\n:"
        case None      => ":"
      }
  }

  private def formatReply(reply: String): Future[String] = formatReply(Some(reply))

  // Process the Galil command and return the reply
  private def processCommand(cmd: ByteString,
                             conn: IncomingConnection): Future[ByteString] = {
    val cmdString = cmd.utf8String
    println(cmdString)

    if (cmdString.startsWith("QR")) {
      // Get the data record from the simulator actor and convert it to a byte string for the response
      val dataRecordF: Future[DataRecord] = simulatorActor ? (ref ⇒ GetDataRecord(ref))
      dataRecordF.map(dr => ByteString(dr.toByteBuffer))
    } else {
      val reply =
        if (cmdString.startsWith("'"))
          formatReply(None) // ignore comment lines starting with with "'"
        else
          try {
            val cmd = cmdString.take(2)
            if (cmdMap.contains(cmd)) {
              formatReply(genericCmd(cmdString)) // XXX TODO change to future
            } else
              cmd match { // basic commands are two upper case chars
                case "" | "BG" | "MO" | "NO" | "SH" | "ST" | "BA" | "BZ" |
                    "HM" | "FI" | "TS" =>
                  simulatorActor ! GalilSimulatorActor.Command()
                  formatReply(None)
                case "TC" => formatReply(tcCmd(cmdString))
                case "TH" => formatReply(thCmd(conn))
                case _    => formatReply(None, isError = true)
              }
          } catch {
            case ex: Throwable =>
              ex.printStackTrace()
              formatReply(None)
          }
      ByteString(reply)
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

  // Simulates commands that let you set and get values, for example:
  //
  // PRA=?
  // PRA=1
  //
  // Command is the first two chars, axis should be the third.
  // If the next two chars are "=?", get the value, otehrwise set it.
  // Return value is the Galil response.
  private def genericCmd(cmdString: String): Future[String] = {
    val cmd = cmdString.take(2)
    val map = cmdMap(cmd)
    val axis = cmdString.drop(2).head
    val value = cmdString.drop(4)
    value match {
      case "?" =>
        val intF: Future[Int] = simulatorActor ? (ref ⇒ AxisGetCommand(cmd, axis, ref))
        intF.map(_.toString)
      case _ =>
        simulatorActor ! AxisSetCommand(cmd, axis, value.toInt)
        Future.successful("")
    }
  }

  //      // MO
  //      Setup(prefix, CommandName("motorOff"), None).add(axisKey.set(axis)),
  //      // DP
  //      Setup(prefix, CommandName("setMotorPosition"), None).add(axisKey.set(axis)).add(countsKey.set(0)),
  //      // PT
  //      Setup(prefix, CommandName("setPositionTracking"), None).add(axisKey.set(axis)).add(countsKey.set(0)),
  //      // MT - Motor Type (stepper)
  //      Setup(prefix, CommandName("setMotorType"), None).add(axisKey.set(axis)).add(countsKey.set(2)),
  //      // AG - Amplifier Gain: Maximum current 1.4A
  //      Setup(prefix, CommandName("setAmplifierGain"), None).add(axisKey.set(axis)).add(countsKey.set(2)),
  //      // YB
  //      Setup(prefix, CommandName("setStepMotorResolution"), None).add(axisKey.set(axis)).add(countsKey.set(200)),
  //      // KS
  //      Setup(prefix, CommandName("setMotorSmoothing"), None).add(axisKey.set(axis)).add(smoothKey.set(8)),
  //      // AC
  //      Setup(prefix, CommandName("setAcceleration"), None).add(axisKey.set(axis)).add(countsKey.set(1024)),
  //      // DC
  //      Setup(prefix, CommandName("setDeceleration"), None).add(axisKey.set(axis)).add(countsKey.set(1024)),
  //      // LC - Low current mode.  setting is a guess.
  //      Setup(prefix, CommandName("setLowCurrent"), None).add(axisKey.set(axis)).add(lcParamKey.set(2)),
  //      // SH
  //      Setup(prefix, CommandName("motorOn"), None).add(axisKey.set(axis)),
  //      // SP - set speed in steps per second
  //      Setup(prefix, CommandName("setMotorSpeed"), None).add(axisKey.set(axis)).add(speedKey.set(25)),
  //  val MotorOff = "MO"
  //  val MotorOn = "SH"
  //  val MotorPosition = "DP"
  //  val PositionTracking = "PT"
  //  val MotorType = "MT"
  //  val AmplifierGain = "AG"
  //  val StepMotorResolution = "YB"
  //  val MotorSmoothing = "KS"
  //  val Acceleration = "AC"
  //  val Deceleration = "DC"
  //  val LowCurrent = "LC"
  //  val MotorSpeed = "SP"
  //  val AbsTarget = "PA"
  //  val BeginMotion = "BG"

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
