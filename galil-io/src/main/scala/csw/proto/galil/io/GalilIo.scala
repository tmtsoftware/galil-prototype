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
    write(sendBuf)
    val result = for (c <- cmds) yield (c, receiveReplies())
    result.toList
  }

  // Receives a replies (up to endMarker) for the given command and returns the result
  // Note: It seems that replies that are longer than bufSize (406 bytes) are broken into
  // multiple responses, so we need to recurse until the whole response has been read.
  private def receiveReplies(result: ByteString = ByteString()): ByteString = {
    val data = read()
    val length = data.length
    if (length == 0) result
    else if (data.takeRight(endMarker.length).utf8String == endMarker)
      result ++ data.dropRight(endMarker.length)
    else if (data.takeRight(1).utf8String == ":") {
      result ++ data.dropRight(1)
    }
//    else if (length < bufSize) {
//      result ++ data
//    }
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
    val sendPacket = new DatagramPacket(sendBuf, sendBuf.length, galilDmcAddress)
    socket.send(sendPacket)
  }

  // Receives a single reply for the given command and returns the result
  override def read(): ByteString = {
    val buf = Array.ofDim[Byte](bufSize)
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
  private val socket = new Socket()
  private val timeoutInMs = 10*1000;   // 10 seconds
  socket.connect(socketAddress, timeoutInMs)

  override def write(sendBuf: Array[Byte]): Unit = {
    socket.getOutputStream.write(sendBuf)
  }

  // Receives a single reply for the given command and returns the result
  override def read(): ByteString = {
    val buf = Array.ofDim[Byte](bufSize)
    val length = socket.getInputStream.read(buf)
    ByteString.fromArray(buf, 0, length)
  }

  override def close(): Unit = socket.close()
}

