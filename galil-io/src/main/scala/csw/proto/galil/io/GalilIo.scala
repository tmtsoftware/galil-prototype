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
    * @return list of (command, reply) from the controller (one pair for each ";" separated command)
    */
  def send(cmd: String): List[(String, ByteString)] = {
    val cmds = cmd.split(';')
    val sendBuf = s"$cmd\r\n".getBytes()
    val galilDmcAddress = new InetSocketAddress(host, port)
    val sendPacket = new DatagramPacket(sendBuf, sendBuf.length, galilDmcAddress)
    socket.send(sendPacket)
    val result = for (c <- cmds) yield (c, receiveReplies())
    result.toList
  }

  // Receives a replies (up to endMarker) for the given command and returns the result
  // Note: It seems that replies that are longer than bufSize (406 bytes) are broken into
  // multiple responses, so we need to recurse until the whole response has been read.
  private def receiveReplies(result: ByteString = ByteString()): ByteString = {
    // Receives a single reply for the given command and returns the result
    def receiveReply(): DatagramPacket = {
      val recvBuf = Array.ofDim[Byte](bufSize)
      val recvPacket = new DatagramPacket(recvBuf, bufSize)
      socket.receive(recvPacket)
      recvPacket
    }

    val packet = receiveReply()
    val data = ByteString(packet.getData)
    val length = packet.getLength
    if (length == 0) result
    else if (data.takeRight(endMarker.length).utf8String == endMarker)
      result ++ data.dropRight(endMarker.length)
    else if (length < bufSize)
      result ++ data
    else receiveReplies(data)
  }
}

object GalilIo {

  // marks end of command or reply (or separator for multiple commands or replies)
  val endMarker = "\r\n:"

  // Max packet size:
  // See http://www.galilmc.com/news/software/using-socket-tcpip-or-udp-communication-galil-controllers
  //  val bufSize: Int = 450
  val bufSize: Int = 406
}

