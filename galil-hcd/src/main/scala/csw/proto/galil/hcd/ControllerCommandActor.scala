package csw.proto.galil.hcd

import java.io.IOException

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import csw.logging.client.scaladsl.LoggerFactory
import csw.proto.galil.hcd.GalilCommandMessage.GalilCommand
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}

import scala.concurrent.duration._

/**
 * Owns the command TCP connection to the Galil DMC-500x0 controller.
 *
 * Responsibilities:
 *   - Opens and maintains the command TCP handle (galilIo)
 *   - Serializes all command traffic: SendCommand, ExecuteProgram, HaltExecution, DownloadProgram
 *   - Manages thread allocation via MG _NO queries
 *   - Reports commandConnection status to InternalStateActor on startup
 *
 * QR polling and analog input queries are handled by ControllerStatusActor on its
 * own independent TCP handle; no status traffic passes through this actor.
 * Console MG output capture is handled by ControllerConsoleActor (hardware-only),
 * spawned as a sibling by GalilHcd after this actor is ready.
 */
private[hcd] object ControllerCommandActor {

  /**
   * Parsed identity of a Galil controller from the ID command response.
   *
   * @param firmware  firmware identifier, e.g. "DMC50040 Rev 1.2a"
   * @param model     DMC model number, e.g. "50000"
   * @param axisCount number of axes supported (extracted from firmware model number), -1 if unknown
   * @param rawResponse full raw response from the ID command
   */
  case class ControllerIdentity(
    firmware: String,
    model: String,
    axisCount: Int,
    rawResponse: String
  )

  /**
   * Pure thread-selection policy (S85): prefer threads 1-7 in ascending
   * order; thread 0 is the LAST RESORT, allocatable only when every other
   * thread is unavailable.
   *
   * Rationale: the controller has 8 threads for up to 8 motors. Reserving
   * thread 0 outright caps simultaneous motion at 7 axes — fine for the
   * 6-motor APS controllers, but a needless limit for a fully-populated one.
   * Thread 0's dedicated jobs (#Init, #Setup, #AUTO) run only while motion
   * commands are gated out (Uninitialized/Initializing/Faulted), so lending
   * it to an eighth concurrent motion is safe; the hardware-busy check below
   * still protects against a genuinely active #Init/#Setup, and a faultReset
   * issued while a motion occupies thread 0 fails cleanly at XQ (operator
   * retries after stopping axes) rather than corrupting state.
   *
   * A candidate must be hardware-free (its bit clear in threadStatus, i.e.
   * MG _NO) AND observed (not awaiting completion attribution — see the
   * unobservedThreads gate in behavior()).
   *
   * @param threadStatus MG _NO bitmask; bit N set = thread N executing
   * @param unobserved   threads reserved pending completion attribution
   * @return the thread to allocate, or None if none is allocatable
   */
  private[hcd] def selectThread(threadStatus: Int, unobserved: Set[Int]): Option[Int] =
    val preference = (1 to 7) :+ 0
    preference.find(t => (threadStatus & (1 << t)) == 0 && !unobserved.contains(t))

  def behavior(
      galilConfig: GalilConfig,
      loggerFactory: LoggerFactory,
      internalStateActor: ActorRef[InternalStateActor.Command],
      simulate: Boolean = false
  ): Behavior[GalilCommandMessage] =
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val log = loggerFactory.getLogger(ctx)

        // Open the command TCP connection
        // var so it can be replaced on successful reconnect
        var galilIo: GalilIo =
          try GalilIoTcp(galilConfig.host, galilConfig.port)
          catch {
            case ex: Exception =>
              log.error(s"Failed to connect to Galil at ${galilConfig.host}:${galilConfig.port}")
              throw ex
          }

        // Report command connection established to InternalStateActor
        internalStateActor ! InternalStateActor.ReportConnectionStatus(
          "commandConnection", ConnectionStatus.Connected
        )

        def galilSend(cmd: String): String = {
          log.debug(s"Sending '$cmd' to Galil")
          val responses = galilIo.send(cmd)
          if (responses.lengthCompare(1) != 0)
            throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
          val resp = responses.head._2.utf8String
          log.debug(s"Response from Galil: $resp")
          resp
        }

        /**
         * Identify the controller by sending ^R^V (firmware version) and ID (board inventory).
         *
         * ^R^V returns a single consistent line on both DMC-400 and DMC-500 series:
         *   DMC-400 series:  "DMC4080 Rev 1.2h1-SIN"
         *   DMC-500 series:  "DMC50040 Rev 1.2a"
         *
         * The model number encodes the axis count in both series:
         *   DMC4080  → 8 axes   (400 series: 4-digit suffix, last digit before 0 = axes)
         *   DMC50040 → 4 axes   (500 series: 5-digit suffix, 4th digit = axes)
         *
         * ID returns connector/board inventory (format differs by series); logged for
         * diagnostics but not parsed for model/axes.
         *
         * @return ControllerIdentity with parsed fields
         * @throws IOException if the controller does not respond or returns unexpected data
         */
        def identifyController(): ControllerIdentity = {
          // Step 1: ^R^V; authoritative firmware version and model string
          val rvResponse = galilSend("\u0012\u0016")
          if (rvResponse.isEmpty || rvResponse == "?")
            throw new IOException(
              s"Controller at ${galilConfig.host}:${galilConfig.port} did not respond to ^R^V"
            )
          val firmware = rvResponse.trim

          // Parse model token (first whitespace-delimited token, e.g. "DMC4080" or "DMC50040")
          val modelToken = firmware.split("\\s+").headOption.getOrElse("unknown")

          // Extract axis count from model number.
          // Both series encode axes as the digit immediately before the trailing '0':
          //   DMC4080  → digit at index 5 = '8'  → 8 axes
          //   DMC50040 → digit at index 5 = '4'  → 4 axes
          // Pattern: "DMC" followed by digits where the second-to-last digit is the axis count.
          val axisCount = {
            val digits = modelToken.dropWhile(!_.isDigit)  // e.g. "4080" or "50040"
            if digits.length >= 2 && digits.last == '0' then
              digits(digits.length - 2).toString.toIntOption.getOrElse(-1)
            else -1
          }

          // Strip leading "DMC" for the model field since the log line prepends "DMC-"
          val model = if modelToken.toUpperCase.startsWith("DMC") then modelToken.drop(3)
                      else modelToken

          // Step 2: ID; board/connector inventory; format varies by series, log as-is
          val idResponse = galilSend("ID")
          val idLines = if idResponse.isEmpty || idResponse == "?" then Seq.empty
                        else idResponse.split("\r?\n").map(_.trim).filter(_.nonEmpty).toSeq

          val identity = ControllerIdentity(
            firmware = firmware,
            model = model,
            axisCount = axisCount,
            rawResponse = s"^R^V: $firmware\nID:\n${idLines.mkString("\n")}"
          )

          log.info(s"Controller identified: firmware=$firmware, model=DMC-$model, axes=$axisCount")
          idLines.foreach(line => log.info(s"  ID: $line"))

          identity
        }

        val controllerIdentity =
          if simulate then
            // Simulator does not implement ^R^V or ID; return a synthetic identity.
            // axisCount=-1 signals "unknown" to GalilHcd (no controllerAxisCount pushed to IS).
            log.info("Simulation mode — skipping controller identification (^R^V / ID)")
            ControllerIdentity(
              firmware    = "Simulator",
              model       = "Simulator",
              axisCount   = -1,
              rawResponse = "Simulator"
            )
          else
            identifyController()

        // ========================================
        // Thread Allocation (from hardware state + observation gate)
        // ========================================
        // Threads 1-7 are preferred for embedded programs; thread 0 (the
        // #Init/#Setup/#AUTO general-purpose thread) is lent out as a last
        // resort so a fully-populated 8-motor controller can move all axes
        // at once (S85; policy in ControllerCommandActor.selectThread).
        // Allocation queries MG _NO directly for hardware state, gated by
        // unobservedThreads below. Hardware-free is necessary but NOT
        // sufficient: a short program (e.g. #StopX on an idle axis) can start
        // and finish entirely between two QR scans, so the hardware shows the
        // thread free while its completion has not yet been observed and
        // attributed by the scan pipeline (CS → IS.ScanObservations).
        // Reusing such a thread would overwrite IS's thread→axis registry
        // entry, misattribute the completion to the new occupant, and orphan
        // the previous command's watcher (stuck Homing symptom, S82).

        // Threads XQ'd by this HCD whose completion has not yet been observed
        // by the scan pipeline. Added on successful XQ; removed on
        // ReleaseThread from IS (sent when IS attributes the completion via
        // ScanObservations, or when CH explicitly unregisters after an HX).
        var unobservedThreads: Set[Int] = Set.empty

        /**
         * Query the controller's active thread bitmask and update IS.
         *
         * @return the thread status bitmask (_NO value)
         */
        def queryThreadStatus(): Int = {
          val noResponses = galilIo.send("MG _NO")
          val noValue = noResponses.head._2.utf8String.trim
          val threadStatus = try {
            noValue.toDouble.toInt
          } catch {
            case _: NumberFormatException =>
              log.warn(s"MG _NO returned unexpected value: '$noValue'")
              0
          }
          // Push real-time thread status to IS
          internalStateActor ! InternalStateActor.UpdateHcdState(
            Map("threadStatus" -> threadStatus),
            ctx.system.ignoreRef)
          threadStatus
        }

        /**
         * Allocate a thread: hardware-free (MG _NO bit clear) AND observed
         * (not awaiting completion attribution). Selection order is the
         * selectThread policy: 1-7 ascending, thread 0 as last resort (S85).
         * A thread-0 allocation is logged at INFO — it means the controller
         * is running programs on every thread it has.
         *
         * The two failure modes are logged distinctly:
         *   - all threads hardware-busy: genuine exhaustion
         *   - hardware-free threads exist but all await observation: transient
         *     back-pressure; clears within one QR scan (CS polls at action rate
         *     while any thread is registered). A persistent recurrence would
         *     indicate a lost ReleaseThread (an invariant bug to investigate,
         *     not to be silently reclaimed here).
         *
         * @return Some(threadNumber) if an allocatable thread exists, None otherwise
         */
        def allocateThread(): Option[Int] = {
          val threadStatus = queryThreadStatus()
          ControllerCommandActor.selectThread(threadStatus, unobservedThreads) match {
            case Some(0) =>
              log.info(s"Allocated thread 0 — LAST RESORT, threads 1-7 exhausted " +
                s"(_NO=0x${threadStatus.toHexString}, " +
                s"unobserved=${unobservedThreads.toSeq.sorted.mkString("[", ",", "]")})")
              Some(0)
            case Some(thread) =>
              log.debug(s"Allocated thread $thread (_NO=0x${threadStatus.toHexString}, " +
                s"unobserved=${unobservedThreads.toSeq.sorted.mkString("[", ",", "]")})")
              Some(thread)
            case None =>
              val hwFree = ((1 to 7) :+ 0).filter(t => (threadStatus & (1 << t)) == 0)
              if (hwFree.nonEmpty)
                log.warn(s"No allocatable threads: hardware-free ${hwFree.mkString("[", ",", "]")} " +
                  s"all awaiting completion observation " +
                  s"(unobserved=${unobservedThreads.toSeq.sorted.mkString("[", ",", "]")}, " +
                  s"_NO=0x${threadStatus.toHexString}). Clears within one QR scan.")
              else
                log.warn(s"No free threads available (_NO=0x${threadStatus.toHexString})")
              None
          }
        }

        Behaviors.receiveMessage[GalilCommandMessage] {
          // Return controller identity; confirms actor setup is complete
          case GalilCommandMessage.GetIdentity(replyTo) =>
            replyTo ! controllerIdentity
            Behaviors.same

          // Release a thread reservation: IS has observed and attributed the
          // thread's completion (or CH explicitly unregistered it after HX).
          // The thread is now allocatable again (subject to hardware state).
          case GalilCommandMessage.ReleaseThread(thread) =>
            if unobservedThreads.contains(thread) then
              log.debug(s"ReleaseThread: thread $thread observed, now allocatable " +
                s"(remaining unobserved=${(unobservedThreads - thread).toSeq.sorted.mkString("[", ",", "]")})")
              unobservedThreads = unobservedThreads - thread
            else
              // Benign: duplicate release (e.g. UnregisterThread raced a scan
              // completion) — idempotent by design.
              log.debug(s"ReleaseThread: thread $thread was not reserved (duplicate release, ignored)")
            Behaviors.same

          // Download current embedded program from controller (UL command).

          //
          // The pre-UL drain below remains as a buffer-hygiene check: the
          // buffer should be empty before any UL, so anything we drain here
          // indicates a new desync somewhere upstream that needs investigation.
          case GalilCommandMessage.DownloadProgram(replyTo) =>
            try {
              // Pre-UL drain: a clean buffer is a precondition for UL.
              // Under normal operation this should always be empty; the
              // post-DL drain in galilIo.uploadProgram and the TCP-actor
              // protocol discipline keep the buffer clean across commands.
              // Logged when non-empty so any future protocol drift is
              // immediately visible rather than silently recovered.
              val preDrain = galilIo.synchronized {
                galilIo.drainAndShowBuffer(timeoutMs = 100)
              }
              if (preDrain.nonEmpty)
                log.warn(s"Pre-UL drain consumed unexpected ${preDrain.length} bytes: '${preDrain.take(80).replace("\r","\\r").replace("\n","\\n")}' — investigate which prior command left this")

              log.info("Downloading program from controller (UL)")
              val program = galilIo.synchronized {
                galilIo.downloadProgram()
              }
              log.info(s"Downloaded program: ${program.length} characters, ${program.linesIterator.size} lines")
              replyTo ! GalilCommandMessage.DownloadProgramResult(program)
            } catch {
              case ex: IOException =>
                log.error(s"Download — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.DownloadProgramResult("", error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"Download failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.DownloadProgramResult("", error = Some(ex.getMessage))
            }
            Behaviors.same

          // Upload an embedded program to the controller (DL command).
          //
          // The DL protocol mechanics (silent-during-stream, "?" detection
          // before "\", two-ack consume after "\", read-timeout management)
          // are encapsulated in GalilIo.uploadProgram.  This handler is
          // responsible only for halting any running threads via HX first
          // and surfacing the result back through the CSW reply protocol.

          case GalilCommandMessage.UploadProgram(program, replyTo) =>
            try {
              log.info(s"Uploading program to controller (DL): ${program.length} characters, ${program.linesIterator.size} lines")
              galilIo.synchronized {
                galilIo.send("HX")
                galilIo.uploadProgram(program)
              }
              log.info("Upload complete")
              replyTo ! GalilCommandMessage.UploadProgramResult(success = true)
            } catch {
              case ex: IOException =>
                log.error(s"Upload — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.UploadProgramResult(success = false, error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"Upload failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.UploadProgramResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // Burn the loaded volatile program to EEPROM (BP command).  Takes
          // 2-3 seconds on hardware; we extend the read timeout to 5s for
          // the burn and restore the original 3s afterward.  All three
          // operations (set timeout, send BP, restore timeout) run inside
          // a single galilIo.synchronized block so no other actor traffic
          // can interleave. Used by faultReset Reload severity.
          case GalilCommandMessage.BurnProgram(replyTo) =>
            try {
              log.info("Burning program to EEPROM (BP)")
              galilIo.synchronized {
                galilIo.setReadTimeout(5000)
                try {
                  // sendAndWaitForPrompt returns Unit on ":" success and
                  // throws RuntimeException on "?" rejection.  No data
                  // response; BP just acks with the prompt when done.
                  galilIo.sendAndWaitForPrompt("BP")
                } finally {
                  galilIo.setReadTimeout(3000)
                }
              }
              log.info("BP complete")
              replyTo ! GalilCommandMessage.BurnProgramResult(success = true)
            } catch {
              case ex: IOException =>
                log.error(s"BP — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.BurnProgramResult(success = false, error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"BP failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.BurnProgramResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // Send the controller-reset command (RS).  RS terminates ALL TCP
          // sessions on the controller as part of the reset, so we use
          // writeRaw (no wait for response) and proactively report the
          // command connection as Disconnected.  Caller must wait for the
          // controller to come back (~5-10s on STB)
          // and then reconnect via Reconnect on this actor and on the
          // status / console actors.  Used by faultReset Reset and Reload.
          case GalilCommandMessage.SendReset(replyTo) =>
            try {
              log.warn("Sending RS — controller will reset embedded state")
              galilIo.synchronized {
                galilIo.writeRaw("RS")
              }
              // RS resets the controller's embedded state, but on the DMC-500
              // series the TCP session is preserved (`:RS` returns to a live `:`
              // prompt without reconnect), so the local socket is not closed here.
              //
              // commandConnection is still marked Disconnected so the rest of the
              // system pauses traffic during the controller's reset window; the
              // upcoming Reconnect call re-marks Connected once the controller is
              // responsive again. Reconnect's drain step clears any pre-RS bytes
              // that may still be sitting in the receive buffer.
              internalStateActor ! InternalStateActor.ReportConnectionStatus(
                "commandConnection", ConnectionStatus.Disconnected)
              replyTo ! GalilCommandMessage.SendResetResult(success = true)
            } catch {
              case ex: IOException =>
                // If the write itself failed the socket is genuinely dead
                // (RS write is a tiny payload; IOException here means TCP
                // is broken, not a controller-side issue).  Mark Disconnected
                // and let the next Reconnect open a fresh socket.
                log.warn(s"RS write — IOException (socket may already be dead): ${ex.getMessage}")
                try galilIo.close() catch case _: Exception => ()
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                // Still report success; RS was attempted; the caller's job
                // is to reconnect, which will tell us whether the controller
                // came back.  Returning failure here would short-circuit
                // recovery for a probably-cosmetic reason.
                replyTo ! GalilCommandMessage.SendResetResult(success = true,
                  error = Some(s"RS write threw IOException (likely already disconnected): ${ex.getMessage}"))
              case ex: Exception =>
                log.error(s"RS failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.SendResetResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // Synchronous command execution for CommandHandlerActor
          // Supports compound commands (semicolon-separated) which return multiple responses
          case GalilCommandMessage.SetReadTimeout(timeoutMs, replyTo) =>
            try {
              galilIo.synchronized { galilIo.setReadTimeout(timeoutMs) }
              val label = s"${timeoutMs}ms"
              log.info(s"Command connection read timeout set to $label")
              replyTo ! GalilCommandMessage.SendCommandResult("")
            } catch {
              case ex: IOException =>
                log.error(s"SetReadTimeout — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.SendCommandResult("", error = Some(ex.getMessage))
              case ex: Exception =>
                replyTo ! GalilCommandMessage.SendCommandResult("", error = Some(ex.getMessage))
            }
            Behaviors.same

          case GalilCommandMessage.SendCommand(commandString, replyTo) =>
            try {
              log.debug(s"SendCommand: $commandString")
              val responses = galilIo.synchronized {
                galilIo.send(commandString)
              }
              // Check for errors in any sub-command response
              val errorResponse = responses.find { case (cmd, bs) =>
                bs.utf8String.trim.startsWith("?")
              }
              errorResponse match {
                case Some((cmd, bs)) =>
                  replyTo ! GalilCommandMessage.SendCommandResult("", 
                    error = Some(s"Command '$cmd' rejected: ${bs.utf8String.trim}"))
                case None =>
                  val allResponses = responses.map(_._2.utf8String.trim).mkString("; ")
                  replyTo ! GalilCommandMessage.SendCommandResult(allResponses)
              }
            } catch {
              case ex: IOException =>
                log.error(s"SendCommand — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.SendCommandResult("", error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"SendCommand failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.SendCommandResult("", error = Some(ex.getMessage))
            }
            Behaviors.same

          // Execute embedded program with automatic thread allocation and start confirmation.
          // Allocates a thread from hardware state (MG _NO), optionally sends preCommands,
          // then sends "XQ #label,thread;MG _XQ<thread>" as one line buffer. The _XQ
          // result reports whether thread N is mid-execution (line >= 0) or has already
          // run to completion (-1). All steps run inside galilIo.synchronized.
          case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands, forceThread) =>
            try {
              galilIo.synchronized {
                // Step 1: Reuse the caller-supplied (already-reserved, just-halted)
                // thread if given; otherwise allocate a fresh one from the pool.
                // Option.orElse is by-name, so allocateThread() and its MG _NO query
                // run only when no forceThread was supplied. (S84)
                forceThread.orElse(allocateThread()) match {
                  case None =>
                    replyTo ! GalilCommandMessage.ExecuteProgramResult(
                      thread = -1, threadWasActive = false,
                      error = Some(s"No threads available to execute #$label"))

                  case Some(thread) =>
                    // Step 2 (optional): Send preCommands before XQ.
                    // Typically a single embedded variable assignment (e.g. "dmd[0]=1000")
                    // or a semicolon-joined compound string. Failure aborts before XQ.
                    val preCommandError: Option[String] = preCommands match {
                      case None => None
                      case Some(cmds) =>
                        log.debug(s"ExecuteProgram: preCommands: $cmds")
                        val preResponses = galilIo.send(cmds)
                        preResponses.find { case (_, bs) =>
                          bs.utf8String.trim.startsWith("?")
                        } match {
                          case Some((_, bs)) =>
                            val msg = s"preCommands '$cmds' rejected: ${bs.utf8String.trim}"
                            log.warn(s"ExecuteProgram: $msg")
                            Some(msg)
                          case None => None
                        }
                    }

                    preCommandError match {
                      case Some(errMsg) =>
                        replyTo ! GalilCommandMessage.ExecuteProgramResult(
                          thread = thread, threadWasActive = false,
                          error = Some(errMsg))

                      case None =>
                        // Step 3: Send XQ + per-thread _XQ query as ONE compound command.
                        //
                        // Why compound: the parser-side MG _XQ<n> runs on the same line
                        // buffer as XQ, eliminating a separate round-trip and giving us
                        // the earliest possible read of the thread's state. On the host
                        // channel the scheduler may switch between the XQ and the MG (the
                        // "no thread switch within a line" rule applies to embedded code,
                        // not host TCP commands), so by the time the MG runs, thread N
                        // has either advanced into its body, or for short programs (e.g.
                        // #StopX is STX;MG;EN) has already completed. Either way the
                        // result is meaningful: a non-(-1) line number means thread N is
                        // mid-execution; -1 means thread N has run and stopped.
                        //
                        // Why _XQ<n> (not _NO): empirically, on this controller firmware,
                        // _NO can show stale "thread active" state for many seconds when
                        // multiple threads are running and one CMDERRs. _XQ<n> is the
                        // authoritative per-thread status: a non-(-1) line number means
                        // the thread is genuinely executing.
                        val xqCmd = s"XQ #$label,$thread;MG _XQ$thread"
                        log.debug(s"ExecuteProgram: sending $xqCmd")
                        val xqResponses = galilIo.send(xqCmd)

                        // Compound returns 2 paired responses. The XQ part responds with
                        // ":" (success) or "?" (rejection). The MG part responds with the
                        // line number or "?" if the prior XQ failed (compound aborts).
                        val xqError = xqResponses.find { case (_, bs) =>
                          bs.utf8String.trim.startsWith("?")
                        }
                        xqError match {
                          case Some((cmd, bs)) =>
                            log.warn(s"ExecuteProgram: $xqCmd rejected: ${bs.utf8String.trim}")
                            replyTo ! GalilCommandMessage.ExecuteProgramResult(
                              thread = thread, threadWasActive = false,
                              error = Some(s"XQ #$label,$thread rejected: ${bs.utf8String.trim}"))

                          case None =>
                            // Step 4: parse the second response (MG _XQ<n> result).
                            // A non-(-1) line number means thread N is mid-execution.
                            // -1 means thread N has already run and stopped (typical for
                            // short programs like #StopX). The caller (typically
                            // executeProgramAndWatch) registers and spawns a watcher in
                            // both cases; the watcher and the next QR scan determine
                            // outcome (whether ae[] was left set, whether motion is in
                            // position, etc).
                            val mgResponse = xqResponses(1)._2.utf8String.trim
                            val xqLine = try mgResponse.toDouble.toInt catch {
                              case _: NumberFormatException =>
                                log.warn(s"ExecuteProgram: MG _XQ$thread returned unexpected value: '$mgResponse'")
                                -1
                            }
                            val threadActive = xqLine >= 0

                            // No threadStatus push to IS here. CS's per-scan _XQ-derived
                            // synthesized threadStatus byte is the single source of truth
                            // (the raw QR threadStatus byte is empirically unreliable
                            // post-CMDERR). The next QR scan in CS reports accurate
                            // state within ~100ms.

                            log.info(s"ExecuteProgram: #$label on thread $thread — " +
                              s"_XQ$thread=$xqLine, ${if threadActive then "ACTIVE" else "already completed"}")

                            // Reserve the thread until its completion is
                            // observed and attributed by the scan pipeline
                            // (released via ReleaseThread from IS). Applies
                            // equally in the "already completed" case: the
                            // program ran, so attribution is still pending.
                            // Reserved only here (successful XQ); rejected XQ
                            // and preCommand failures never start the program,
                            // so the thread stays allocatable.
                            unobservedThreads = unobservedThreads + thread

                            replyTo ! GalilCommandMessage.ExecuteProgramResult(
                              thread = thread, threadWasActive = threadActive, error = None)
                        }
                    }
                }
              }
            } catch {
              case ex: IOException =>
                log.error(s"ExecuteProgram — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = -1, threadWasActive = false, error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"ExecuteProgram failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = -1, threadWasActive = false,
                  error = Some(s"ExecuteProgram failed: ${ex.getMessage}"))
            }
            Behaviors.same

          // Halt an active execution thread (SDD 4.8.1).
          //
          // The thread parameter comes from IS.activeThread (set by CommandHandlerActor when
          // the program started). It confirms via MG _NO before sending HX; if the thread
          // bit is no longer set, the program already finished and HX is skipped.
          //
          // This handler only kills the thread. The caller is responsible for any motor
          // stop (ST) or embedded stop program (#StopX) as appropriate for the use case.
          case GalilCommandMessage.HaltExecution(thread, axis, replyTo) =>
            try {
              galilIo.synchronized {
                if thread >= 1 then
                  val threadStatus = queryThreadStatus()
                  val threadBit = 1 << thread
                  if (threadStatus & threadBit) != 0 then
                    val hxCmd = s"HX $thread"
                    log.info(s"HaltExecution: thread $thread confirmed active (_NO=0x${threadStatus.toHexString}), sending $hxCmd")
                    val hxResponses = galilIo.send(hxCmd)
                    val hxError = hxResponses.find { case (_, bs) =>
                      bs.utf8String.trim.startsWith("?")
                    }
                    if hxError.isDefined then
                      log.warn(s"HaltExecution: HX $thread returned error — thread may have stopped between check and HX")
                      replyTo ! GalilCommandMessage.HaltExecutionResult(success = false,
                        error = Some(s"HX $thread returned error"))
                    else
                      log.info(s"HaltExecution: axis ${axis.char} thread $thread halted")
                      replyTo ! GalilCommandMessage.HaltExecutionResult(success = true)
                  else
                    log.info(s"HaltExecution: thread $thread already finished (_NO=0x${threadStatus.toHexString}), skipping HX")
                    replyTo ! GalilCommandMessage.HaltExecutionResult(success = true)
                else
                  log.info(s"HaltExecution: thread=0, nothing to halt")
                  replyTo ! GalilCommandMessage.HaltExecutionResult(success = true)
              }
            } catch {
              case ex: IOException =>
                log.error(s"HaltExecution — command connection lost: ${ex.getMessage}")
                internalStateActor ! InternalStateActor.ReportConnectionStatus(
                  "commandConnection", ConnectionStatus.Disconnected)
                replyTo ! GalilCommandMessage.HaltExecutionResult(success = false, error = Some(ex.getMessage))
              case ex: Exception =>
                log.error(s"HaltExecution failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.HaltExecutionResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // Attempt to verify and if necessary re-establish the command TCP connection.
          // Step 1: test existing socket with MG 0. If that succeeds, report Connected and done.
          // Step 2: if test fails, close dead socket, open fresh GalilIoTcp, retest.
          // Reports commandConnection Connected/Disconnected to IS in either outcome.
          //
          // Buffer hygiene: drain the receive buffer before testing
          // and after a successful test to clear any stale data left over from
          // pre-fault traffic.  Without this, a stale response chunk ending in
          // ":" can confuse the next multi-chunk read (notably UL during
          // verifyEmbeddedProgram) and short-circuit termination; the post-RS
          // STB regression that motivated this drain.  Status actor already
          // does this; command actor now mirrors the pattern.
          case GalilCommandMessage.Reconnect(replyTo) =>
            log.info(s"Reconnect: verifying command connection to ${galilConfig.host}:${galilConfig.port}")

            def drainBuffer(io: GalilIo): Unit =
              val stale = io.drainAndShowBuffer(timeoutMs = 500)
              if (stale.nonEmpty)
                log.info(s"Reconnect: drained ${stale.length} bytes of stale buffer data")

            def testCurrentSocket(): Boolean =
              try
                galilIo.synchronized {
                  // Pre-drain: clear any stale buffered data so the MG 0
                  // response isn't mixed in with prior unread bytes.
                  drainBuffer(galilIo)
                  val responses = galilIo.send("MG 0")
                  responses.nonEmpty // any response means socket is alive
                }
              catch
                case _: Exception => false

            def openFreshSocket(): Either[String, GalilIo] =
              try
                val newIo = GalilIoTcp(galilConfig.host, galilConfig.port)
                // Verify the new socket with a test command
                newIo.synchronized {
                  newIo.send("MG 0")
                }
                Right(newIo)
              catch
                case ex: Exception =>
                  Left(s"Failed to open new command connection: ${ex.getMessage}")

            if testCurrentSocket() then
              log.info("Reconnect: existing command socket is working")
              // Post-drain: clear anything that arrived during/after the MG 0
              // exchange; for example, late data from before the disconnect.
              galilIo.synchronized { drainBuffer(galilIo) }
              internalStateActor ! InternalStateActor.ReportConnectionStatus(
                "commandConnection", ConnectionStatus.Connected
              )
              replyTo ! GalilCommandMessage.ReconnectResult(success = true)
            else
              log.info("Reconnect: existing command socket unresponsive — closing and opening new connection")
              try galilIo.close() catch case _: Exception => ()

              openFreshSocket() match
                case Right(newIo) =>
                  galilIo = newIo
                  // Post-drain on fresh socket too; controllers can emit an
                  // initial prompt on accept that we want consumed before any
                  // commands flow.
                  galilIo.synchronized { drainBuffer(galilIo) }
                  log.info("Reconnect: new command connection established")
                  internalStateActor ! InternalStateActor.ReportConnectionStatus(
                    "commandConnection", ConnectionStatus.Connected
                  )
                  replyTo ! GalilCommandMessage.ReconnectResult(success = true)

                case Left(errMsg) =>
                  log.error(s"Reconnect: $errMsg")
                  internalStateActor ! InternalStateActor.ReportConnectionStatus(
                    "commandConnection", ConnectionStatus.Disconnected
                  )
                  replyTo ! GalilCommandMessage.ReconnectResult(success = false, error = Some(errMsg))

            Behaviors.same

          // GalilCommand, QRResult, DownloadProgramResult, SendCommandResult are reply-direction
          // message types that should never be sent to this actor. Log and ignore.
          case unexpected =>
            log.warn(s"ControllerCommandActor received unexpected message: $unexpected")
            Behaviors.same

        }.receiveSignal {
          case (_, org.apache.pekko.actor.typed.PostStop) =>
            // Send ST;MO before closing the socket; this is the safe-state
            // policy that protects motors on every shutdown path (HMI Shutdown
            // button, supervisor Restart, FailureStop exception, container
            // shutdown, etc.). The send is synchronous because PostStop has
            // no mailbox processing; we go directly through galilIo.
            //
            // Idempotent: ST on stationary axes and MO on already-disabled
            // drives are both no-ops on the controller side.
            //
            // Failures (controller unreachable, broken socket) are caught
            // and logged; we always proceed to the socket close so PostStop
            // completes within the framework's 10s budget.
            log.info("ControllerCommandActor stopping — sending ST;MO before closing")
            try {
              galilIo.synchronized {
                val responses = galilIo.send("ST;MO")
                val errored = responses.exists { case (_, bs) => bs.utf8String.trim.startsWith("?") }
                if (errored) log.warn(s"ControllerCommandActor PostStop: ST;MO returned error: ${responses.map(_._2.utf8String.trim).mkString("; ")}")
                else log.info("ControllerCommandActor PostStop: ST;MO acknowledged")
              }
            } catch {
              case ex: Exception =>
                log.warn(s"ControllerCommandActor PostStop: ST;MO failed (${ex.getMessage}) — continuing close")
            }
            log.info("ControllerCommandActor stopping — closing command connection")
            try galilIo.close() catch { case _: Exception => () }
            Behaviors.same
        }
      }
    }
}