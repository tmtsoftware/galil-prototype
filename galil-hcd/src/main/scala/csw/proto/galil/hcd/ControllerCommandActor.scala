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
 * own independent TCP handle — no status traffic passes through this actor.
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
         * ID returns connector/board inventory (format differs by series) — logged for
         * diagnostics but not parsed for model/axes.
         *
         * @return ControllerIdentity with parsed fields
         * @throws IOException if the controller does not respond or returns unexpected data
         */
        def identifyController(): ControllerIdentity = {
          // Step 1: ^R^V — authoritative firmware version and model string
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

          // Step 2: ID — board/connector inventory; format varies by series, log as-is
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
            // Simulator does not implement ^R^V or ID — return a synthetic identity.
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
        // Thread Allocation (from hardware state)
        // ========================================
        // Thread 0 is reserved for #Init / general purpose.
        // Threads 1-7 are available for embedded programs.
        // Allocation queries MG _NO directly — the hardware IS the pool.
        // No separate bookkeeping needed; MG _NO is always authoritative.

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
         * Allocate a free thread by querying hardware directly.
         * Thread 0 is reserved. Searches threads 1-7 for one not active in _NO.
         *
         * @return Some(threadNumber) if a thread is free, None if all busy
         */
        def allocateThread(): Option[Int] = {
          val threadStatus = queryThreadStatus()
          // Find first thread 1-7 that is not active
          (1 to 7).find { t =>
            val bit = 1 << t
            (threadStatus & bit) == 0
          } match {
            case Some(thread) =>
              log.debug(s"Allocated thread $thread (_NO=0x${threadStatus.toHexString})")
              Some(thread)
            case None =>
              log.warn(s"No free threads available (_NO=0x${threadStatus.toHexString})")
              None
          }
        }

        Behaviors.receiveMessage[GalilCommandMessage] {
          // Return controller identity — confirms actor setup is complete
          case GalilCommandMessage.GetIdentity(replyTo) =>
            replyTo ! controllerIdentity
            Behaviors.same

          // Download current embedded program from controller (UL command)
          case GalilCommandMessage.DownloadProgram(replyTo) =>
            try {
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
          case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands) =>
            try {
              galilIo.synchronized {
                // Step 1: Allocate a thread (queries MG _NO for free bits, updates IS)
                allocateThread() match {
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
          // the program started). CI confirms via MG _NO before sending HX — if the thread
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
          case GalilCommandMessage.Reconnect(replyTo) =>
            log.info(s"Reconnect: verifying command connection to ${galilConfig.host}:${galilConfig.port}")

            def testCurrentSocket(): Boolean =
              try
                galilIo.synchronized {
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
            // Send ST;MO before closing the socket — this is the safe-state
            // policy that protects motors on every shutdown path (HMI Shutdown
            // button, supervisor Restart, FailureStop exception, container
            // shutdown, etc.). The send is synchronous because PostStop has
            // no mailbox processing — we go directly through galilIo.
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