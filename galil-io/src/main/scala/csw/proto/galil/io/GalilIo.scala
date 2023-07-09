package csw.proto.galil.io

import akka.util.ByteString

import GalilIo._

/**
 * Based class for a TCP/UDP socket client talking to a Galil controller.
 */
abstract class GalilIo {

  /**
   * Writes the data to the socket
   */
  protected def write(sendBuf: Array[Byte]): Unit

  /**
   * Reads the reply from the socket and returns it as a ByteString
   */
  protected def read(): ByteString

  /**
   * Closes the socket connection to the Galil controller
   * (Do not use this object after closing the socket).
   */
  def close(): Unit

  // From the Galil doc:
  // 2) Sending a Command
  // Once a socket is established, the user will need to send a Galil command as a string to
  // the controller (via the opened socket) followed by a Carriage return (0x0D).
  // 3) Receiving a Response
  // "The controller will respond to that command with a string. The response of the
  // command depends on which command was sent. In general, if there is a
  // response expected such as the "TP" Tell Position command. The response will
  // be in the form of the expected value(s) followed by a Carriage return (0x0D), Line
  // Feed (0x0A), and a Colon (:). If the command was rejected, the response will be
  // just a question mark (?) and nothing else. If the command is not expected to
  // return a value, the response will be just the Colon (:)."

  /**
   * Sends a command to the controller and returns a list of responses
   *
   * @param cmd command to pass to the controller (May contain multiple commands separated by ";")
   * @return list of (command, reply) from the controller (one pair for each ";" separated command)
   */
  def send(cmd: String): List[(String, ByteString)] = {
    val cmds    = cmd.split(';')
    val sendBuf = s"$cmd\r\n".getBytes()
    write(sendBuf)
    val result = for (c <- cmds) yield (c, receiveReplies())
    result.toList
  }

  // Receives a reply (up to endMarker) for the given command and returns the result
  // Note: Replies that are longer than bufSize (406 bytes) are broken into
  // multiple responses, so we need to recurse until the whole response has been read.
  // ASCII responses end with "\r\n:", while binary responses end with ":".
  private def receiveReplies(result: ByteString = ByteString()): ByteString = {
    val data   = read()
    val length = data.length
    if (length == 0) result
    else if (length == 1 && data.utf8String == "?")
      result ++ data
    else if (data.takeRight(endMarker.length).utf8String == endMarker)
      result ++ data.dropRight(endMarker.length)
    else if (data.takeRight(separator.length).utf8String == separator)
      result ++ data.dropRight(separator.length)
    else if (data.takeRight(1).utf8String == ":") {
      result ++ data.dropRight(1)
    }
    else
      result ++ data // Should not happen?
  }
}

object GalilIo {

  // separator for multiple commands or replies
  val separator = "\r\n"

  // marks end of command or reply
  val endMarker = "\r\n:"

  // Max packet size:
  // See http://www.galilmc.com/news/software/using-socket-tcpip-or-udp-communication-galil-controllers
  //  val bufSize: Int = 450
  val bufSize: Int = 406
}

/**
 * A UDP socket based client for talking to a Galil controller.
 *
 * @param host    the Galil controller host
 * @param port    the Galil controller port
 */
case class GalilIoUdp(host: String = "127.0.0.1", port: Int = 8888) extends GalilIo {
  import java.net.{DatagramPacket, DatagramSocket, InetSocketAddress}

  private val socket = new DatagramSocket()

  override def write(sendBuf: Array[Byte]): Unit = {
    val galilDmcAddress = new InetSocketAddress(host, port)
    val sendPacket      = new DatagramPacket(sendBuf, sendBuf.length, galilDmcAddress)
    socket.send(sendPacket)
  }

  // Receives a single reply for the given command and returns the result
  override def read(): ByteString = {
    val buf    = Array.ofDim[Byte](bufSize)
    val packet = new DatagramPacket(buf, bufSize)
    socket.receive(packet)
    ByteString.fromArray(packet.getData, packet.getOffset, packet.getLength)
  }

  override def close(): Unit = socket.close()
}

/**
 * A TCP socket based client for talking to a Galil controller.
 *
 * @param host    the Galil controller host
 * @param port    the Galil controller port
 */
case class GalilIoTcp(host: String = "127.0.0.1", port: Int = 8888) extends GalilIo {
  import java.net.InetAddress
  import java.net.InetSocketAddress
  import java.net.Socket

  private val socketAddress = new InetSocketAddress(InetAddress.getByName(host), port)
  private val socket        = new Socket()
  private val timeoutInMs   = 3 * 1000; // 3 seconds

  // XXX TODO: Error handling when there is no device available!
  socket.connect(socketAddress, timeoutInMs)

  override def write(sendBuf: Array[Byte]): Unit = {
    socket.getOutputStream.write(sendBuf)
  }

  // Receives a single reply for the given command and returns the result
  override def read(): ByteString = {
    val buf    = Array.ofDim[Byte](bufSize)
    val length = socket.getInputStream.read(buf)
    ByteString.fromArray(buf, 0, length)
  }

  override def close(): Unit = socket.close()
}
