package csw.proto.galil.simulatorRepl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source, Tcp}
import akka.util.ByteString

import scala.concurrent.Future
import scala.io.StdIn
import scala.util.{Failure, Success}

object GalilReplClient extends App {

  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  import system.dispatcher

  case class Options(host: String = "127.0.0.1", port: Int = 8888)

  // Parses the command line options
  private val parser = new scopt.OptionParser[Options]("test-akka-service-app") {
    head("simulatorrepl", System.getProperty("CSW_VERSION"))

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
    val connection = Tcp().outgoingConnection(host, port)

    def quit(f: Future[Done]): Unit = {
      f.onComplete {
        case Success(_) =>
          system.terminate()
        case Failure(ex) =>
          println(s"Error : $ex")
          system.terminate()
      }
    }

    val replParser = Flow[String]
      // Need to send an initial message to start off: (NO = No op)
      .merge(Source.single("NO"))
      // Type 'q' to quit
      .takeWhile(_ != "q")
      .watchTermination() { (_, f) => quit(f) }
      .map(elem => ByteString(s"$elem\r"))

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
    //
    // Here, the string "ERROR" is returned for an error ("?"), "OK" for an empty response
    // and otherwise the response is returned (minus the trailing delimiter).
    val responseHandler = Flow[ByteString].map { bs =>
      val s = bs.utf8String
      s match {
        case "?" => "?"
        case ":" => ""
        case resp if resp.endsWith("\r\n:") => resp.dropRight(3)
        case _ => "INCOMPLETE" // XXX should not happen
      }
    }

    val version = Option(System.getProperty("VERSION")).getOrElse("")
    println(s"Galil client $version: type 'q' to quit.")

    val repl = Flow[ByteString]
      .via(responseHandler)
      .map(response => println(s"$response"))
      .map((_: Unit) => StdIn.readLine(":"))
      // client side comments with REM? Convert to server format with "'"
      .map(s => s.replaceFirst("^REM", "'"))
      .via(replParser)

    connection.join(repl).run
  }
}
