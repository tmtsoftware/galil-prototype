package csw.proto.galil.simulator

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}


/**
 * Simulates the protocol used to talk to the Galil hardware.
 */
object GalilSimulatorApp {

  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "GalilSimulatorApp")

  private case class Options(host: String = "127.0.0.1", port: Int = 8888, debug: Boolean = false)

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("test-pekko-service-app") {
    head("simulator", Option(System.getProperty("VERSION")).getOrElse("dev"))

    opt[String]("host") valueName "<hostname>" action { (x, c) =>
      c.copy(host = x)
    } text "the host to bind to (default: 127.0.0.1)"

    opt[Int]("port") valueName "<n>" action { (x, c) =>
      c.copy(port = x)
    } text "the port number on host (default: 8888)"

    opt[Unit]("debug") action { (_, c) =>
      c.copy(debug = true)
    } text "enable verbose debug logging"

    help("help")
    version("version")
  }

  def main(args: Array[String]): Unit = {

    // Parse the command line options
    parser.parse(args, Options()) match {
      case Some(options) =>
        try {
          run(options)
        }
        catch {
          case e: Throwable =>
            e.printStackTrace()
            System.exit(1)
        }
      case None => System.exit(1)
    }
  }

  private def run(options: Options): Unit = {
    import options._
    val simulator = GalilSimulator(host, port, debug)
    println(s"Simulator started on $host:$port - press Ctrl+C to exit")
    if (debug) println("Debug logging enabled")
    
    // Keep the main thread alive indefinitely
    Thread.currentThread().join()
  }
}