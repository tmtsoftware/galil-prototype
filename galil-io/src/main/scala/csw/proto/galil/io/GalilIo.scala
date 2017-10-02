package csw.proto.galil.io

import akka.actor.ActorSystem
import akka.util.{ByteString, Timeout}
import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}

import csw.services.logging.scaladsl.ComponentLogger

import scala.concurrent.duration._

object GalilIoLogger extends ComponentLogger("GalilIo")

/**
  * A client for talking to a Galil controller (or the "simulator" application).
  *
  * Note that with the current implementation, it is not possible to send a command
  * before the previous command completes (Doing so will result in an error).
  *
  * @param host    the Galil controller host
  * @param port    the Galil controller port
  * @param system  Akka environment used to create worker actor
  * @param timeout max amount of time to wait for reply from controller (default: 10 secs)
  */
case class GalilIo(host: String = "127.0.0.1", port: Int = 8888)
                  (implicit system: ActorSystem,
                   timeout: Timeout = Timeout(10.seconds)) extends GalilIoLogger.Simple {

  import GalilIo._

  private val socket = new DatagramSocket()


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
    *
    * @param cmd command to pass to the controller (May contain multiple commands separated by ";")
    * @return the list of replies from the controller, which may be ASCII or binary, depending on the command
    */
  def send(cmd: String): List[ByteString] = {
    val numCmds = cmd.split(';').length
    val sendBuf = s"$cmd\r\n".getBytes()
    val galilDmcAddress = new InetSocketAddress(host, port)
    val sendPacket = new DatagramPacket(sendBuf, sendBuf.length, galilDmcAddress)
    // 406 bytes is the maximum size of response message from Galil DMC-4020 in one UDP packet.
    val recvBuf = Array.ofDim[Byte](406)
    socket.send(sendPacket)
    val result = for(i <- 0 until numCmds) yield {
      val recvPacket = new DatagramPacket(recvBuf, recvBuf.length)
      socket.receive(recvPacket)
      val data = ByteString(recvPacket.getData)
      // XXX TODO: Could make the next bit more efficient (Do bibary results have the end marker?)
      data.utf8String.split(endMarker).toList.map {
        case ":" => ""
        case "?" => "error"
        case x => x
      }.map(ByteString(_)).head
    }
    result.toList
  }
}

object GalilIo {

  // marks end of command or reply (or separator for multiple commands or replies)
  val endMarker = "\r\n:"
}

