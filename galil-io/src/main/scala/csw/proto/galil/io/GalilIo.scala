package csw.proto.galil.io

import org.apache.pekko.util.ByteString

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
   * Writes raw data to the socket without waiting for a response.
   * Used for streaming commands like DL (program download) where
   * responses don't come after each line.
   * 
   * @param data string to write (will add \r\n terminator)
   */
  def writeRaw(data: String): Unit = {
    val sendBuf = s"$data\r\n".getBytes()
    write(sendBuf)
  }

  /**
   * Drains input buffer and shows what data is present.
   * Waits up to timeoutMs for data to arrive before returning.
   * 
   * This is a safety net for debugging and error recovery.
   * In normal operation with proper protocol implementation, this should
   * return empty strings. If it finds data, that indicates a protocol bug.
   * 
   * @param timeoutMs maximum time to wait for data (default 200ms)
   * @return string of data read from buffer
   */
  def drainAndShowBuffer(timeoutMs: Int = 200): String

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
    
    if (cmds.length == 1) {
      // Single command — use existing receiveReplies() directly
      List((cmds.head, receiveReplies()))
    } else {
      // Compound command — collect all responses, then pair with commands.
      // Galil sends one ":" per sub-command, but they may arrive in a single TCP packet
      // (e.g. "speed[0]=500;accel[0]=9216" returns "::" as one read).
      // We read until we have the expected number of colon-delimited responses.
      val allResponses = receiveCompoundReplies(cmds.length)
      cmds.zip(allResponses).toList
    }
  }

  /**
   * Sends a command and waits only for the acknowledgment prompt (:).
   * Used for commands that don't return data, just confirmation.
   * 
   * @param cmd command to send
   * @throws RuntimeException if response is not ":" or command was rejected ("?")
   */
  def sendAndWaitForPrompt(cmd: String): Unit = {
    val responses = send(cmd)
    if (responses.size != 1) {
      throw new RuntimeException(s"Expected 1 response to '$cmd', got ${responses.size}")
    }
    val (_, response) = responses.head
    val responseStr = response.utf8String.trim
    // Empty response means we got ":" which was stripped by receiveReplies
    // "?" means command was rejected
    if (responseStr == "?") {
      throw new RuntimeException(s"Command '$cmd' rejected by controller")
    }
    // Otherwise, empty or ":" is success
  }

  /**
   * Downloads program from controller using UL command.
   * Properly implements the UL protocol.
   * 
   * @return program text from controller (without backslash terminator)
   */
  def downloadProgram(): String = {
    val responses = send("UL")
    if (responses.size != 1) {
      throw new RuntimeException(s"Expected 1 response to UL, got ${responses.size}")
    }
    
    val program = responses.head._2.utf8String
    
    // UL terminates with backslash - remove it if present
    program
      .stripSuffix("\\")
      .stripSuffix("\u001A")  // Control-Z (alternative terminator)
      .trim
  }

  /**
   * Uploads program to controller using DL command.
   * Properly implements the DL protocol.
   * 
   * CRITICAL: DL does not respond until the program terminator (\) is sent.
   * The controller waits for program data after receiving DL.
   * 
   * @param program the program text to upload
   */
  def uploadProgram(program: String): Unit = {
    // Step 1: Enter DL mode - controller does NOT respond yet, waits for data
    writeRaw("DL")
    
    // Step 2: Stream program data (no response expected per line)
    writeRaw(program)
    
    // Step 3: Send terminator - controller responds with ":" only now
    sendAndWaitForPrompt("\\")
  }

  // Receives multiple replies for a compound command (semicolon-separated).
  // Galil sends one ":" per sub-command, but for commands that don't return data
  // (like variable assignments), multiple ":" may arrive in a single TCP read.
  // This method accumulates data and splits on ":" boundaries until we have
  // the expected number of responses.
  //
  // Response formats:
  //   Assignment (speed[0]=500):   ":"
  //   Query (MG speed[0]):         "500.0000\r\n:"
  //   Error:                       "?"
  //
  // So a compound like "speed[0]=500;MG speed[0]" returns ":\r\n500.0000\r\n:"
  // and "speed[0]=500;accel[0]=9216" returns "::"
  private def receiveCompoundReplies(expectedCount: Int): Array[ByteString] = {
    var accumulated = ByteString.empty
    var responses = Array.empty[ByteString]
    
    while (responses.length < expectedCount) {
      val data = read()
      if (data.isEmpty) {
        // Timeout or connection closed — return what we have, padded with empty
        return responses.padTo(expectedCount, ByteString.empty)
      }
      accumulated = accumulated ++ data
      
      // Split accumulated data on ":" boundaries
      responses = splitResponses(accumulated)
    }
    
    responses.take(expectedCount)
  }

  // Split raw Galil response data into individual responses.
  // Each response is terminated by ":". Data responses include "\r\n" before the ":".
  // We split on ":" and clean up each segment.
  // Split raw Galil response data into individual responses.
  // Each response is terminated by ":". Data responses include "\r\n" before the ":".
  // We split on ":" and clean up each segment.
  //
  // Java's String.split(":") drops trailing empty strings, so ":::" returns Array().
  // We must use split(":", -1) to preserve them: ":::" → Array("", "", "", "")
  private def splitResponses(data: ByteString): Array[ByteString] = {
    val str = data.utf8String
    // A complete compound response ends with ":" (success) or "?" (error on last sub-command).
    // Without this check we'd loop forever if the last sub-command returns an error on hardware.
    if (!str.endsWith(":") && !str.endsWith("?")) return Array.empty  // incomplete response
    
    // Normalise: replace "?" error terminators with ":" so we can split uniformly.
    // Each "?" represents one rejected sub-command response.
    val normalised = str.replace("?", ":")
    
    val parts = normalised.split(":", -1)  // -1 preserves trailing empty strings
    // For ":::" → ["", "", "", ""]  (4 parts, 3 responses)
    // For "500.0000\r\n:" → ["500.0000\r\n", ""]  (2 parts, 1 response)
    // For ":500.0000\r\n:" → ["", "500.0000\r\n", ""]  (3 parts, 2 responses)
    // Last element is always "" (after final ":"), so drop it.
    // Remaining elements are the response data (one per sub-command).
    val responses = parts.dropRight(1)  // drop trailing empty after last ":"
    responses.map { part =>
      ByteString(part.stripSuffix("\r\n").stripSuffix("\r"))
    }
  }

  // Receives a reply (up to endMarker) for the given command and returns the result
  // Note: Replies that are longer than bufSize (406 bytes) are broken into
  // multiple responses, so we need to recurse until the whole response has been read.
  // ASCII responses end with "\r\n:", while binary responses end with ":".
  private def receiveReplies(result: ByteString = ByteString()): ByteString = {
    val data   = read()
    val length = data.length
    
    // DEBUG: Show what we received and what terminators we're checking
    // if (length > 0) {
    //   val preview = if (length > 50) data.utf8String.take(50) + "..." else data.utf8String
    //   val endChars = if (length >= 3) data.takeRight(3).utf8String.map(c => s"'$c'(${c.toInt})").mkString(" ") else ""
    //   println(s"DEBUG GalilIo.receiveReplies: Read $length bytes, end chars: [$endChars], preview: $preview")
    // }
    
    if (length == 0) result
    else if (length == 1 && data.utf8String == "?")
      result ++ data
    else if (data.takeRight(endMarker.length).utf8String == endMarker) {
      // println(s"DEBUG GalilIo: Found endMarker '\\r\\n:', complete")
      result ++ data.dropRight(endMarker.length)
    }
    // REMOVED separator check - it was stopping at line endings instead of response end
    // else if (data.takeRight(separator.length).utf8String == separator) {
    //   println(s"DEBUG GalilIo: Found separator '\\r\\n', complete")
    //   result ++ data.dropRight(separator.length)
    // }
    else if (data.takeRight(1).utf8String == ":") {
      // println(s"DEBUG GalilIo: Found colon ':', complete")
      result ++ data.dropRight(1)
    }
    else {
      // Response incomplete - recurse to read more
      // println(s"DEBUG GalilIo: Response incomplete ($length bytes, total so far: ${result.length + data.length}), recursing...")
      receiveReplies(result ++ data)
    }
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

  // UDP doesn't have the same buffering as TCP - just return empty
  override def drainAndShowBuffer(timeoutMs: Int = 200): String = {
    ""
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
  socket.setSoTimeout(timeoutInMs)  // Set read timeout to prevent infinite blocking

  override def write(sendBuf: Array[Byte]): Unit = {
    socket.getOutputStream.write(sendBuf)
    socket.getOutputStream.flush()  // Force immediate send to controller
  }

  // Receives a single reply for the given command and returns the result
  override def read(): ByteString = {
    val buf    = Array.ofDim[Byte](bufSize)
    val length = socket.getInputStream.read(buf)
    ByteString.fromArray(buf, 0, length)
  }

  /**
   * Drains input buffer with timeout to ensure data is actually read.
   * 
   * This waits with timeout to ensure we catch all data.
   * In normal operation with proper protocol implementation, this should
   * return empty strings. If it finds data, that indicates a protocol bug.
   * 
   * @param timeoutMs maximum time to wait for data
   * @return all data read from buffer
   */
  override def drainAndShowBuffer(timeoutMs: Int = 200): String = {
    val result = new StringBuilder
    val startTime = System.currentTimeMillis()
    val oldTimeout = socket.getSoTimeout
    
    try {
      // Set a short timeout for non-blocking reads
      socket.setSoTimeout(timeoutMs)
      
      // Keep reading until timeout or no more data
      var continue = true
      while (continue && (System.currentTimeMillis() - startTime) < timeoutMs) {
        try {
          val available = socket.getInputStream.available()
          if (available > 0) {
            val buf = Array.ofDim[Byte](Math.min(available, 1000))
            val length = socket.getInputStream.read(buf)
            if (length > 0) {
              result.append(ByteString.fromArray(buf, 0, length).utf8String)
            } else {
              continue = false
            }
          } else {
            // No data available - wait a bit and check again
            Thread.sleep(10)
          }
        } catch {
          case _: java.net.SocketTimeoutException =>
            // Timeout - no more data coming
            continue = false
        }
      }
    } finally {
      // Restore original timeout
      socket.setSoTimeout(oldTimeout)
    }
    
    result.toString
  }

  override def close(): Unit = socket.close()
}