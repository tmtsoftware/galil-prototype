package csw.proto.galil.simulatorRepl

import akka.actor.ActorSystem
import akka.util.ByteString
import csw.proto.galil.io.GalilIo

import scala.io.StdIn
import scala.util.{Failure, Success}

/**
  * A command line REPL for a Galil controller (or simulator).
  */
object GalilRepl extends App {
  implicit val system: ActorSystem = ActorSystem()

  case class Options(host: String = "127.0.0.1", port: Int = 8888)

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("test-akka-service-app") {
    head("simulatorrepl", System.getProperty("VERSION"))

    opt[String]("host") valueName "<hostname>" action { (x, c) =>
      c.copy(host = x)
    } text "the host to connect to (default: 127.0.0.1)"

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
    import system.dispatcher

    val galilIo = GalilIo(host, port)

    def loop(): Unit = {
      val cmd = StdIn.readLine(":")
      if (cmd == "q") {
        system.terminate()
      } else {
        galilIo.send(cmd).onComplete {
          case Success(result) =>
            result.foreach(r => println(formatResult(cmd, r)))
            loop()
          case Failure(ex) =>
            println(s"error: $ex")
            loop()
        }
      }
    }

    loop()
  }

  def formatResult(cmd: String, result: ByteString): String = {
    cmd match {
      case "QR" => QrCmd.format(result)
      case _ => result.utf8String
    }
  }
}
