package csw.proto.galil.simulator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Framing, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.ByteString

import scala.concurrent.Future

/**
  * Simulates the protocol used to talk to the Galil hardware.
  */
object GalilSimulator extends App {

  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  case class Options(host: String = "127.0.0.1", port: Int = 8888)

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("test-akka-service-app") {
    head("simulator", System.getProperty("CSW_VERSION"))

    opt[String]("host") valueName "<hostname>" action { (x, c) =>
      c.copy(host = x)
    } text "the host to bind to (default: 127.0.0.1)"

    opt[Int]("port") valueName "<n>" action { (x, c) =>
      c.copy(port = x)
    } text "the port number on host (default: 8888)"

    help("help")
    version("version")
  }

  // Parse the command line options
  parser.parse(args, Options()) match {
    case Some(options) =>
      try {
        run(options)
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          System.exit(1)
      }
    case None => System.exit(1)
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
  private def formatReply(reply: Option[String], isError: Boolean = false): String = {
    if (isError) "?" else reply match {
      case Some(msg) => s"$msg\r\n:"
      case None => ":"
    }
  }
  private def formatReply(reply: String): String = formatReply(Some(reply))

  // Process the Galil command and return the reply
  private def processCommand(cmd: String): String = {
    cmd match {
      case "badcmd" => formatReply(None, isError = true)
      case "noreplycmd" => formatReply(None)
      case _ => formatReply(s"$cmd!!!")
    }
  }

  private def run(options: Options): Unit = {
    import options._

    val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(host, port)

    connections.runForeach { connection =>
      // server logic, parses incoming commands
      val commandParser = Flow[String].takeWhile(_ != "BYE").map(processCommand)

      val serverLogic = Flow[ByteString]
        .via(Framing.delimiter(ByteString("\r"), maximumFrameLength = 256, allowTruncation = true))
        .map(_.utf8String)
        .via(commandParser)
        .map(ByteString(_))

      connection.handleWith(serverLogic)
    }
  }
}
