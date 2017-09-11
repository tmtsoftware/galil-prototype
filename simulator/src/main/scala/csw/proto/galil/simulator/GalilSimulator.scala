package csw.proto.galil.simulator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source, Tcp}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.util.ByteString

import scala.concurrent.Future

/**
  * Simulates the protocol used to talk to the Galil hardware.
  */
object GalilSimulator extends App {

  import akka.stream.scaladsl.Framing

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

  private def run(options: Options): Unit = {
    import options._

    val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind(host, port)

    //  connections runForeach { connection =>
    //    println(s"New connection from: ${connection.remoteAddress}")
    //
    //    val echo = Flow[ByteString]
    //      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
    //      .map(_.utf8String)
    //      .map(_ + "!!!\n")
    //      .map(ByteString(_))
    //
    //    connection.handleWith(echo)
    //  }

    connections.runForeach { connection =>
      // server logic, parses incoming commands
      val commandParser = Flow[String].takeWhile(_ != "BYE").map(_ + "!")

      import connection._
      val welcomeMsg = s"Welcome to: $localAddress, you are: $remoteAddress!"
      val welcome = Source.single(welcomeMsg)

      val serverLogic = Flow[ByteString]
        .via(Framing.delimiter(ByteString("\r"), maximumFrameLength = 256, allowTruncation = true))
        .map(_.utf8String)
        .via(commandParser)
        // merge in the initial banner after parser
        .merge(welcome)
        .map(_ + "\r")
        .map(ByteString(_))

      connection.handleWith(serverLogic)
    }
  }
}
