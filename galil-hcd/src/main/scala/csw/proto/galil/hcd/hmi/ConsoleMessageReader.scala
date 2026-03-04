package csw.proto.galil.hcd.hmi

import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop, Props}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import csw.logging.api.scaladsl.Logger

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, Socket, SocketTimeoutException}
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/**
 * Reads unsolicited MG (Message) output from the Galil controller.
 *
 * Opens a dedicated TCP connection to the controller (claiming a new
 * handle, e.g. IHD) and sends "CF I" to redirect unsolicited message
 * output to this handle. Then reads MG output continuously.
 *
 * This is the standard Galil pattern — the same approach GalilTools
 * uses for its console window. Only one handle receives MG output at
 * a time (determined by CF), so while the HMI is running, GalilTools'
 * console will be silent. When the HCD shuts down and this handle
 * disconnects, the controller reverts CF to the previous handle.
 *
 * Lifecycle: created by HmiServer, stopped on HCD shutdown.
 */
object ConsoleMessageReader {

  sealed trait Command
  case object Start extends Command
  case object Stop extends Command
  case class GetMessages(replyTo: ActorRef[Messages]) extends Command

  case class ConsoleLine(timestamp: Instant, text: String)
  case class Messages(lines: Seq[ConsoleLine])

  private val MaxBufferSize = 500
  private val ConnectTimeoutMs = 5000
  private val ReadTimeoutMs = 3000

  /**
   * @param host    Controller IP address
   * @param port    Controller TCP port (usually 23)
   * @param onLine  Callback invoked for each received console line
   * @param log     CSW logger
   */
  def apply(
    host: String,
    port: Int,
    onLine: ConsoleLine => Unit,
    log: Logger
  ): Behavior[Command] = Behaviors.setup { ctx =>
    val buffer = new ConcurrentLinkedDeque[ConsoleLine]()

    @volatile var running = false
    @volatile var readerThread: Thread = null

    def startReading(): Unit = {
      if (running) return
      running = true

      readerThread = new Thread(() => {
        var socket: Socket = null
        try {
          // Open a new TCP connection — this claims a new handle (e.g. IHD)
          log.info(s"ConsoleMessageReader: connecting to $host:$port")
          socket = new Socket()
          socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), ConnectTimeoutMs)
          socket.setSoTimeout(ReadTimeoutMs)
          log.info("ConsoleMessageReader: connected, sending CF I to claim MG output")

          // Read and discard initial prompt (colon)
          val initBuf = new Array[Byte](256)
          try {
            val n = socket.getInputStream.read(initBuf)
            if (n > 0) {
              val init = new String(initBuf, 0, n, "UTF-8").trim
              log.debug(s"ConsoleMessageReader: initial read: '$init'")
            }
          } catch {
            case _: SocketTimeoutException => // OK — no initial prompt
          }

          // Send "CF I" to redirect unsolicited messages to this handle.
          // The "I" argument means "this handle" (the handle issuing the command).
          val cfCmd = "CF I\r"
          socket.getOutputStream.write(cfCmd.getBytes("UTF-8"))
          socket.getOutputStream.flush()

          // Read CF response (should be ":" prompt)
          try {
            val n = socket.getInputStream.read(initBuf)
            if (n > 0) {
              val resp = new String(initBuf, 0, n, "UTF-8").trim
              log.info(s"ConsoleMessageReader: CF I response: '$resp'")
            }
          } catch {
            case _: SocketTimeoutException =>
              log.info("ConsoleMessageReader: CF I sent (no response, OK)")
          }

          // Send "CW 2" to switch unsolicited messages to ASCII format.
          // CW 2 configures the handle for ASCII text output of MG messages.
          // (CW 1 is data adjustment only; CW 2 is the correct setting for
          // readable MG output on a TCP handle.)
          val cwCmd = "CW 2\r"
          socket.getOutputStream.write(cwCmd.getBytes("UTF-8"))
          socket.getOutputStream.flush()

          // Read CW response
          try {
            val n = socket.getInputStream.read(initBuf)
            if (n > 0) {
              val resp = new String(initBuf, 0, n, "UTF-8").trim
              log.info(s"ConsoleMessageReader: CW 2 response: '$resp'")
            }
          } catch {
            case _: SocketTimeoutException =>
              log.info("ConsoleMessageReader: CW 2 sent (no response, OK)")
          }

          log.info("ConsoleMessageReader: listening for MG output")

          // Now just read — all MG output from embedded programs will arrive here
          val readBuf = new Array[Byte](4096)
          val lineBuffer = new StringBuilder()
          var totalBytesRead = 0L
          var readAttempts = 0L

          while (running) {
            try {
              val n = socket.getInputStream.read(readBuf)
              readAttempts += 1
              if (n < 0) {
                log.warn("ConsoleMessageReader: connection closed by controller")
                running = false
              } else if (n > 0) {
                totalBytesRead += n
                val text = new String(readBuf, 0, n, "UTF-8")
                log.debug(s"ConsoleMessageReader: received $n bytes (total=$totalBytesRead): ${text.take(200).replace("\r","\\r").replace("\n","\\n")}")
                lineBuffer.append(text)

                // Extract complete lines (MG output terminated with CR+LF)
                var nlIdx = lineBuffer.indexOf('\n')
                while (nlIdx >= 0) {
                  val rawLine = lineBuffer.substring(0, nlIdx).stripSuffix("\r").trim
                  lineBuffer.delete(0, nlIdx + 1)

                  // Filter out empty lines, lone colons (prompt), and ? (error echo)
                  if (rawLine.nonEmpty && rawLine != ":" && rawLine != "?") {
                    val line = ConsoleLine(Instant.now(), rawLine)

                    buffer.addLast(line)
                    while (buffer.size() > MaxBufferSize) {
                      buffer.pollFirst()
                    }

                    try { onLine(line) } catch { case NonFatal(_) => }
                  }

                  nlIdx = lineBuffer.indexOf('\n')
                }
              }
            } catch {
              case _: SocketTimeoutException =>
                readAttempts += 1
                // Log heartbeat every ~30s (10 timeouts at 3s each) so we know loop is alive
                if (readAttempts % 10 == 0) {
                  log.debug(s"ConsoleMessageReader: alive, $readAttempts reads, $totalBytesRead bytes received so far")
                }
                // Normal — no MG output during this period, loop back
              case e: IOException if running =>
                log.warn(s"ConsoleMessageReader: read error: ${e.getMessage}")
                running = false
            }
          }
        } catch {
          case e: IOException =>
            if (running) {
              log.error(s"ConsoleMessageReader: connection failed: ${e.getMessage}")
            }
          case NonFatal(e) =>
            log.error(s"ConsoleMessageReader: unexpected error: ${e.getMessage}")
        } finally {
          if (socket != null && !socket.isClosed) {
            try { socket.close() } catch { case NonFatal(_) => }
          }
          log.info("ConsoleMessageReader: stopped")
        }
      }, "galil-console-reader")

      readerThread.setDaemon(true)
      readerThread.start()
    }

    def stopReading(): Unit = {
      running = false
      if (readerThread != null) {
        readerThread.interrupt()
        readerThread = null
      }
    }

    Behaviors.receiveMessage[Command] {
      case Start =>
        startReading()
        Behaviors.same

      case Stop =>
        stopReading()
        Behaviors.stopped

      case GetMessages(replyTo) =>
        replyTo ! Messages(buffer.asScala.toSeq)
        Behaviors.same

    }.receiveSignal {
      case (_, PostStop) =>
        stopReading()
        Behaviors.same
    }
  }
}