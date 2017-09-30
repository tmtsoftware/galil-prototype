package csw.proto.galil.io

import akka.actor.ActorSystem
import akka.actor.{ActorRef, Props}
import akka.io.{IO, Tcp, Udp}
import akka.util.{ByteString, Timeout}
import java.net.InetSocketAddress

import akka.pattern.ask
import Tcp._
import csw.services.logging.scaladsl.ComponentLogger

import scala.concurrent.Future
import scala.concurrent.duration._

object GalilIoLogger extends ComponentLogger("GalilIo")

/**
  * A client for talking to a Galil controller (or the "simulator" application).
  * @param host the Galil controller host
  * @param port the Galil controller port
  * @param system Akka environment used to create worker actor
  * @param timeout max amount of time to wait for reply from controller (default: 10 secs)
  */
case class GalilIo(host: String = "127.0.0.1", port: Int = 8888)
                  (implicit system: ActorSystem,
                   timeout: Timeout = Timeout(10.seconds)) extends GalilIoLogger.Simple {

  import GalilIo._
  import GalilClientActor._
  import system.dispatcher

  private val workerActor = system.actorOf(GalilWorkerActor.props(host, port))

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

  /**
    * Sends a command to the controller and returns a list of responses
    * @param cmd command to pass to the controller (May contain multiple commands separated by ";")
    * @return the list of replies from the controller, which may be ASCII or binary, depending on the command
    */
  def send(cmd: String): Future[List[ByteString]] = {
    val f = workerActor ? SendData(ByteString(s"$cmd\r"))
    f.map {
      case ReceivedData(data) =>
        data.utf8String.split("\r\n:").toList.map {
          case ":" => ""
          case "?" => "error"
          case x => x
        }.map(ByteString(_))

      case _ => Nil
    }
  }
}

object GalilIo {

  private object GalilWorkerActor {
    def props(host: String, port: Int) =
      Props(new GalilWorkerActor(host, port))
  }

  private class GalilWorkerActor(host: String, port: Int) extends GalilIoLogger.Actor {

    import GalilClientActor._

    private val remoteSocket = new InetSocketAddress(host, port)
    private val clientActor = context.actorOf(GalilClientActor.props(remoteSocket, self))

    def receive: Receive = {
      case ConnectFailed =>
        log.error("Connect failed")

      case Connected(c) =>
        log.info(s"Connected: $c") // Not needed?

      case SendData(data) =>
        log.info(s"sending ${data.utf8String}")
        clientActor ! data
        context.become(waitForResponse(sender()))
    }

    def waitForResponse(replyTo: ActorRef): Receive = {
      case ConnectFailed =>
        log.error("Connect failed")
        replyTo ! ConnectFailed
        context.become(receive)

      case WriteFailed =>
        log.error("Write failed")
        replyTo ! WriteFailed
        context.become(receive)

      case Connected(c) =>
        log.info(s"Connected: $c") // Not needed?

      case ConnectionClosed =>
        log.info(s"Connection closed")

      case r: ReceivedData =>
        replyTo ! r
        context.become(receive)
    }
  }


  private object GalilClientActor {
    def props(remoteSocket: InetSocketAddress, listener: ActorRef) =
      Props(new GalilClientActor(remoteSocket, listener))

    trait ResponseMessage

    case object ConnectFailed extends ResponseMessage

    case object WriteFailed extends ResponseMessage

    case class Connected(c: Tcp.Connected) extends ResponseMessage

    case object ConnectionClosed extends ResponseMessage

    case class ReceivedData(data: ByteString) extends ResponseMessage

    case class SendData(data: ByteString) extends ResponseMessage

  }

  private class GalilClientActor(remoteSocket: InetSocketAddress, listener: ActorRef) extends GalilIoLogger.Actor {

    import context.system

    IO(Udp) ! Connect(remoteSocket)

    def receive: Receive = {
      case CommandFailed(_: Connect) =>
        listener ! GalilClientActor.ConnectFailed
        context stop self

      case c@Connected(_, _) =>
        listener ! GalilClientActor.Connected(c)
        val connection = sender()
        connection ! Register(self)
        context.become(connected(connection))

      case x => log.error(s"Unexpected message $x")
    }

    private def connected(connection: ActorRef): Receive = {
      case data: ByteString =>
        connection ! Write(data)
      case CommandFailed(_: Write) =>
        // O/S buffer was full
        listener ! GalilClientActor.WriteFailed
      case Received(data) =>
        listener ! GalilClientActor.ReceivedData(data)
      case "close" =>
        connection ! Close
      case _: ConnectionClosed =>
        listener ! GalilClientActor.ConnectionClosed
        context stop self
      case x => log.error(s"Unexpected message $x")
    }
  }

}

