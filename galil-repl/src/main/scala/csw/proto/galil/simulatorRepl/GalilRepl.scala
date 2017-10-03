package csw.proto.galil.simulatorRepl

import akka.actor.ActorSystem
import akka.util.ByteString
import csw.proto.galil.io.{DataRecord, GalilIoTcp, GalilIoUdp}

import scala.io.StdIn

/**
  * A command line REPL for a Galil controller (or simulator).
  */
object GalilRepl extends App {
  implicit val system: ActorSystem = ActorSystem()

  case class Options(host: String = "127.0.0.1", port: Int = 8888, protocol: String = "tcp")

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("test-akka-service-app") {
    head("simulatorrepl", System.getProperty("VERSION"))

    opt[String]("host") valueName "<hostname>" action { (x, c) =>
      c.copy(host = x)
    } text "the host to connect to (default: 127.0.0.1)"

    opt[Int]("port") valueName "<n>" action { (x, c) =>
      c.copy(port = x)
    } text "the port number on host (default: 8888)"

    opt[String]("protocol") valueName "<udp|tcp>" action { (x, c) =>
      c.copy(protocol = x)
    } text "the protocol to use: one of 'tcp' or 'udp' (default: tcp)"

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

    val galilIo = if (protocol == "udp") GalilIoUdp(host, port) else GalilIoTcp(host, port)

    def loop(): Unit = {
      val cmd = StdIn.readLine(":")
      if (cmd == "q") {
        system.terminate()
      } else {
        try {
          val resultList = galilIo.send(cmd)
          resultList.foreach(r => println(formatResult(r._1, r._2)))
        } catch {
          case ex: Exception =>
            println(s"error: $ex")
        }
        loop()
      }
    }

    loop()
  }

  private def formatResult(cmd: String, result: ByteString): String = {
    if (cmd == "QR" || cmd.startsWith("QR "))
      DataRecord(result).toString
    else
      result.utf8String
  }
}
