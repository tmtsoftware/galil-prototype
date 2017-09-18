package csw.proto.galil.simulator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

/**
  * Simulates the protocol used to talk to the Galil hardware.
  */
object GalilSimulatorApp extends App {

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
    GalilSimulator(host, port)
  }
}
