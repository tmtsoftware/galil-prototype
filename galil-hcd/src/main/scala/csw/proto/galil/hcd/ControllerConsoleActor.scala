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
 *   The ControllerCommandActor's Behaviors.setup block waits on this latch (up to
 *   ReadyTimeoutMs) before returning. Because GalilHcd.initialize() blocks on
 *   GetIdentity after spawning the ControllerCommandActor, the wait is inline in
 *   that chain, guaranteeing the console handle is active before #Init or #SetupX
 *   execute.
 *
 *   On any connection failure the latch is also counted down, so the caller
 *   is never left blocking indefinitely; it logs a warning and continues.
 *
 * Reconnect:
 *   Used by faultReset Reset and Reload severities. The controller's RS command
 *   drops all TCP sessions, so the console handle must be re-established after the
 *   controller comes back. The Reconnect message stops the existing reader thread
 *   (closing its socket so the in-progress read unblocks promptly), waits for it to
 *   terminate, then starts a fresh connect + CF I + CW 2 sequence. Replies
 *   success/failure on a fresh CountDownLatch with a generous ReconnectTimeoutMs budget.
 *
 * CF routing:
 *   Only one TCP handle receives MG output at a time (determined by CF I).
 *   On HCD shutdown this handle closes and CF reverts to the prior handle.
 *
 * Simulation:
 *   Not spawned in simulation mode (ControllerCommandActor enforces this).
 */
object ControllerConsoleActor:

  sealed trait Command
  case object Stop extends Command

  /**
   * Re-establish the console TCP handle. Used by faultReset Reset and Reload
   * severities. Stops any in-flight reader thread, waits briefly for it to
   * terminate, then starts a fresh connect + CF I + CW 2 sequence. Replies
   * success/failure once the new handle is live (or has failed to come up).
   *
   * Console is informational and excluded from isOperational, so callers
   * can choose to ignore failure here without losing operational status.
   */
  case class Reconnect(replyTo: ActorRef[ReconnectResult]) extends Command

  /**
   * Outcome of a Reconnect attempt.  success=true means the new handle is
   * connected, CF I + CW 2 have been sent, and the reader thread is now
   * looping for MG output.
   */
  case class ReconnectResult(success: Boolean, error: Option[String] = None)

  /**
   * Outcome the reader thread reports back to whoever asked it to start
   * (initial setup or a Reconnect message). Internal, not exposed.
   */
  private sealed trait ConnectOutcome
  private case object ConnectSucceeded extends ConnectOutcome
  private case class ConnectFailed(reason: String) extends ConnectOutcome

  private val ConnectTimeoutMs = 5000
  private val ReadTimeoutMs    = 3000

  /** Maximum time to wait for a reconnect attempt to complete (open socket
   *  + CF I + CW 2).  Generous compared to the 5s socket connect timeout
   *  so an in-progress connect can finish.  Reset/Reload recovery uses
   *  this; the existing first-connect latch is governed by ReadyTimeoutMs. */
  val ReconnectTimeoutMs = 8000

  /**
   * Maximum time to wait for the previous reader thread to terminate before
   * starting a new one on Reconnect.  Generous (5s) because the thread may
   * be blocked in a socket read; we interrupt it and wait briefly.
   */
  val StopWaitMs = 5000

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

    @volatile var running           = false
    @volatile var connectionLostFlag = false
    @volatile var readerThread: Thread = null
    @volatile var currentSocket: Socket = null

    /**
     * Start a reader thread.  onOutcome is called once per startReading
     * call, when the thread either:
     *   - completes the connect + CF I + CW 2 handshake (ConnectSucceeded)
     *   - fails at any step before the handshake (ConnectFailed)
     * The thread continues looping for MG output after success.
     *
     * Idempotent guard: if running is already true, onOutcome is invoked
     * with ConnectSucceeded immediately (the existing thread is fine).
     */
    def startReading(onOutcome: ConnectOutcome => Unit): Unit =
      if running then
        onOutcome(ConnectSucceeded)
        return
      running = true
      connectionLostFlag = false

      readerThread = new Thread(
        () => {
          var socket: Socket = null
          // Tracks whether onOutcome has already been invoked for this
          // thread instance, so we don't double-fire on shutdown paths.
          var outcomeReported = false
          def report(outcome: ConnectOutcome): Unit =
            if !outcomeReported then
              outcomeReported = true
              try onOutcome(outcome) catch case NonFatal(_) => ()

          try
            log.info(s"$logTag ControllerConsoleActor: connecting to $host:$port")
            socket = new Socket()
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), ConnectTimeoutMs)
            socket.setSoTimeout(ReadTimeoutMs)
            currentSocket = socket
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
            report(ConnectSucceeded)

            // ── Read loop ────────────────────────────────────────────────────
            val readBuf    = new Array[Byte](4096)
            val lineBuffer = new StringBuilder()

            while running do
              try
                val n = socket.getInputStream.read(readBuf)
                if n < 0 then
                  log.warn(s"$logTag ControllerConsoleActor: connection closed by controller")
                  connectionLostFlag = true
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
                  connectionLostFlag = true
                  running = false
                case _: IOException =>
                  // running is already false — we were asked to stop and the
                  // socket was closed from stopReading() to unblock this read.
                  // Loop will exit on the next iteration check.

            // If we exited the loop because of a connection loss (not a Stop command),
            // report Disconnected. This is informational only — consoleConnection is
            // excluded from isOperational — but ensures the HMI accurately reflects
            // that the console handle is gone.
            if connectionLostFlag then
              internalStateActor ! InternalStateActor.ReportConnectionStatus(
                "consoleConnection", ConnectionStatus.Disconnected
              )

          catch
            case e: IOException =>
              log.error(s"$logTag ControllerConsoleActor: connection failed: ${e.getMessage}")
              report(ConnectFailed(e.getMessage)) // never leave caller blocking
            case NonFatal(e) =>
              log.error(s"$logTag ControllerConsoleActor: unexpected error: ${e.getMessage}")
              report(ConnectFailed(e.getMessage)) // never leave caller blocking
          finally
            if socket != null && !socket.isClosed then
              try socket.close() catch case NonFatal(_) => ()
            currentSocket = null
            log.info(s"$logTag ControllerConsoleActor: stopped")
            // Backstop: if the thread exited before the handshake completed
            // (e.g. immediate IOException not caught by the outer try), still
            // unblock any waiter.
            report(ConnectFailed("reader thread exited before handshake"))
        },
        s"galil-console-${prefix.componentName}"
      )

      readerThread.setDaemon(true)
      readerThread.start()

    def stopReading(): Unit =
      running = false
      // Close the socket so any in-progress blocking read in the reader
      // thread throws and the thread exits promptly.  setSoTimeout (3s)
      // would also eventually unblock the read, but closing is faster.
      val s = currentSocket
      if s != null && !s.isClosed then
        try s.close() catch case NonFatal(_) => ()
      val t = readerThread
      if t != null then
        t.interrupt()
        readerThread = null

    /**
     * Stop the existing reader thread and wait briefly for it to terminate
     * before returning. Used by Reconnect to ensure the old socket and thread
     * are fully gone before starting a fresh one, avoiding two threads reading
     * the same (or different) sockets and racing on internalStateActor
     * notifications.
     */
    def stopAndWait(): Unit =
      val t = readerThread
      stopReading()
      if t != null then
        try t.join(StopWaitMs.toLong) catch case _: InterruptedException => ()
        if t.isAlive then
          log.warn(s"$logTag ControllerConsoleActor: previous reader thread did not exit within ${StopWaitMs}ms")

    // ── Initial start ────────────────────────────────────────────────────
    // First connect — count down the readyLatch on outcome regardless of
    // success or failure, mirroring the old behaviour where startReading
    // unconditionally called readyLatch.countDown() to never leave the
    // GalilHcd init waiter blocking.
    startReading(_ => readyLatch.countDown())

    Behaviors
      .receiveMessage[Command] {
        case Stop =>
          stopReading()
          Behaviors.stopped

        case Reconnect(replyTo) =>
          // Synchronous-style reconnect handled inside the actor message
          // loop.  The reader thread does its work on its own thread; we
          // just orchestrate stop → start and wait briefly for the new
          // handshake to complete (or fail).
          log.info(s"$logTag ControllerConsoleActor: Reconnect requested")
          stopAndWait()

          // CountDownLatch + outcome holder so we can deliver the result
          // back from the reader thread to this actor message handler.
          val latch  = new CountDownLatch(1)
          @volatile var outcome: ConnectOutcome = ConnectFailed("no outcome reported")
          startReading { o =>
            outcome = o
            latch.countDown()
          }

          val finished = latch.await(ReconnectTimeoutMs.toLong, java.util.concurrent.TimeUnit.MILLISECONDS)
          if !finished then
            log.warn(s"$logTag ControllerConsoleActor: Reconnect timed out after ${ReconnectTimeoutMs}ms")
            replyTo ! ReconnectResult(success = false,
              error = Some(s"Reconnect handshake did not complete within ${ReconnectTimeoutMs}ms"))
          else outcome match
            case ConnectSucceeded =>
              replyTo ! ReconnectResult(success = true)
            case ConnectFailed(reason) =>
              replyTo ! ReconnectResult(success = false, error = Some(reason))
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        stopReading()
        Behaviors.same
      }
  }