package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.{ActorRef, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import csw.logging.api.scaladsl.Logger
import csw.prefix.models.Prefix

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, Socket, SocketTimeoutException}
import java.util.concurrent.CountDownLatch
import scala.util.control.NonFatal

/**
 * Reads unsolicited MG (Message) output from the Galil DMC-500x0 controller
 * and emits each line as a CSW log message.
 *
 * Architecture:
 *   - Spawned as a sibling of ControllerCommandActor and ControllerStatusActor
 *     by GalilHcd after the command connection is established (hardware-only).
 *   - Opens a dedicated third TCP handle and sends CF I + CW 2 to claim
 *     unsolicited MG output on this handle in ASCII format.
 *   - Each MG line from an embedded DMC program is emitted as:
 *       log.info(s"[GALIL:$prefix] $text")
 *     routing through CSW logging (FileAppender, StdOutAppender, HmiLogAppender).
 *   - No knowledge of HMI, WebSocket, or frontend.
 *
 * Startup synchronisation:
 *   The caller passes a CountDownLatch(1). The read thread counts it down
 *   immediately after CF I + CW 2 complete (handle is live and receiving).
 *   The CI actor's Behaviors.setup block waits on this latch (up to
 *   ReadyTimeoutMs) before returning. Because GalilHcd.initialize() blocks on
 *   GetIdentity after spawning CI actor, the wait is inline in that chain —
 *   guaranteeing the console handle is active before #Init or #SetupX execute.
 *
 *   On any connection failure the latch is also counted down, so the caller
 *   is never left blocking indefinitely; it logs a warning and continues.
 *
 * CF routing:
 *   Only one TCP handle receives MG output at a time (determined by CF I).
 *   On HCD shutdown this handle closes and CF reverts to the prior handle.
 *
 * Simulation:
 *   Not spawned in simulation mode (CI actor enforces this).
 */
object ControllerConsoleActor:

  sealed trait Command
  case object Stop extends Command

  private val ConnectTimeoutMs = 5000
  private val ReadTimeoutMs    = 3000

  /** CI actor waits at most this long for CF I + CW 2 to complete before
   *  proceeding with #Init. Well under the CSW 10s init timeout. */
  val ReadyTimeoutMs = 4000

  /**
   * @param host       Controller IP address
   * @param port       Controller TCP port (usually 23)
   * @param prefix     HCD component prefix, used as log tag
   * @param log                 CSW logger
   * @param readyLatch          Counted down once CF I + CW 2 complete (or on failure).
   * @param internalStateActor  IS actor to report consoleConnection status to.
   */
  def apply(
    host:                String,
    port:                Int,
    prefix:              Prefix,
    log:                 Logger,
    readyLatch:          CountDownLatch,
    internalStateActor:  ActorRef[InternalStateActor.Command]
  ): Behavior[Command] = Behaviors.setup { _ =>

    val logTag = s"[GALIL:$prefix]"

    @volatile var running      = false
    @volatile var readerThread: Thread = null

    def startReading(): Unit =
      if running then return
      running = true

      readerThread = new Thread(
        () => {
          var socket: Socket = null
          try
            log.info(s"$logTag ControllerConsoleActor: connecting to $host:$port")
            socket = new Socket()
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), ConnectTimeoutMs)
            socket.setSoTimeout(ReadTimeoutMs)
            log.info(s"$logTag ControllerConsoleActor: connected")

            val buf = new Array[Byte](256)

            // Discard the initial colon prompt
            try
              val n = socket.getInputStream.read(buf)
              if n > 0 then
                log.debug(s"$logTag ControllerConsoleActor: initial prompt: '${new String(buf, 0, n, "UTF-8").trim}'")
            catch
              case _: SocketTimeoutException => // no prompt — OK

            // CF I — direct unsolicited MG output to this handle
            socket.getOutputStream.write("CF I\r".getBytes("UTF-8"))
            socket.getOutputStream.flush()
            try
              val n = socket.getInputStream.read(buf)
              if n > 0 then
                log.debug(s"$logTag ControllerConsoleActor: CF I response: '${new String(buf, 0, n, "UTF-8").trim}'")
            catch
              case _: SocketTimeoutException =>
                log.debug(s"$logTag ControllerConsoleActor: CF I sent (no response, OK)")

            // CW 2 — ASCII text format for MG output on this handle
            socket.getOutputStream.write("CW 2\r".getBytes("UTF-8"))
            socket.getOutputStream.flush()
            try
              val n = socket.getInputStream.read(buf)
              if n > 0 then
                log.debug(s"$logTag ControllerConsoleActor: CW 2 response: '${new String(buf, 0, n, "UTF-8").trim}'")
            catch
              case _: SocketTimeoutException =>
                log.debug(s"$logTag ControllerConsoleActor: CW 2 sent (no response, OK)")

            // ── Ready ────────────────────────────────────────────────────────
            // CF I + CW 2 complete: handle is live. Report connection status and
            // unblock GalilHcd so it proceeds to #Init knowing all MG output is captured.
            log.info(s"$logTag ControllerConsoleActor: listening for MG output")
            internalStateActor ! InternalStateActor.ReportConnectionStatus(
              "consoleConnection", ConnectionStatus.Connected
            )
            readyLatch.countDown()

            // ── Read loop ────────────────────────────────────────────────────
            val readBuf    = new Array[Byte](4096)
            val lineBuffer = new StringBuilder()

            while running do
              try
                val n = socket.getInputStream.read(readBuf)
                if n < 0 then
                  log.warn(s"$logTag ControllerConsoleActor: connection closed by controller")
                  running = false
                else if n > 0 then
                  lineBuffer.append(new String(readBuf, 0, n, "UTF-8"))

                  var nlIdx = lineBuffer.indexOf('\n')
                  while nlIdx >= 0 do
                    val line = lineBuffer.substring(0, nlIdx).stripSuffix("\r").trim
                    lineBuffer.delete(0, nlIdx + 1)
                    // Filter prompts (:) and error echoes (?) — log everything else
                    if line.nonEmpty && line != ":" && line != "?" then
                      log.info(s"$logTag $line")
                    nlIdx = lineBuffer.indexOf('\n')

              catch
                case _: SocketTimeoutException => // no MG output this period — normal
                case e: IOException if running =>
                  log.warn(s"$logTag ControllerConsoleActor: read error: ${e.getMessage}")
                  running = false

          catch
            case e: IOException =>
              log.error(s"$logTag ControllerConsoleActor: connection failed: ${e.getMessage}")
              readyLatch.countDown() // never leave caller blocking
            case NonFatal(e) =>
              log.error(s"$logTag ControllerConsoleActor: unexpected error: ${e.getMessage}")
              readyLatch.countDown() // never leave caller blocking
          finally
            if socket != null && !socket.isClosed then
              try socket.close() catch case NonFatal(_) => ()
            log.info(s"$logTag ControllerConsoleActor: stopped")
        },
        s"galil-console-${prefix.componentName}"
      )

      readerThread.setDaemon(true)
      readerThread.start()

    def stopReading(): Unit =
      running = false
      if readerThread != null then
        readerThread.interrupt()
        readerThread = null

    startReading()

    Behaviors
      .receiveMessage[Command] { case Stop =>
        stopReading()
        Behaviors.stopped
      }
      .receiveSignal { case (_, PostStop) =>
        stopReading()
        Behaviors.same
      }
  }