package csw.proto.galil.hcd

import java.io.IOException

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
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
        val galilIo: GalilIo =
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
         * Identify the controller by sending the ID command.
         * Runs during actor setup to verify the connection and log hardware identity.
         *
         * Real Galil response example (DMC-500):
         *   FW, DMC50040 Rev 1.2a
         *   DMC, 50000, Rev 0
         *   CMB, 41023, 3.3v, Rev 1
         *   AMP1, 44020, Rev 0
         *
         * The firmware model number encodes the number of axes:
         *   DMC-500x0 where x = axis count (e.g., 50040 = 4 axes, 50080 = 8 axes)
         *
         * @return ControllerIdentity with parsed fields
         * @throws IOException if the controller does not respond or returns unexpected data
         */
        def identifyController(): ControllerIdentity = {
          val response = galilSend("ID")
          if (response.isEmpty || response == "?")
            throw new IOException(
              s"Controller at ${galilConfig.host}:${galilConfig.port} did not respond to ID command"
            )

          val lines = response.split("\r?\n").map(_.trim).filter(_.nonEmpty)

          // Parse firmware line: "FW, DMC50040 Rev 1.2a"
          val fwLine = lines.find(_.startsWith("FW,"))
          val firmware = fwLine.map(_.stripPrefix("FW,").trim).getOrElse("unknown")

          // Parse DMC model line: "DMC, 50000, Rev 0"
          val dmcLine = lines.find(_.startsWith("DMC,"))
          val model = dmcLine.map(_.stripPrefix("DMC,").trim.split(",").head.trim).getOrElse("unknown")

          // Extract axis count from firmware model number: DMC500x0 -> x is the axis digit
          val axisCount = fwLine.flatMap { fw =>
            val pattern = """DMC5\d{2}(\d)0""".r
            pattern.findFirstMatchIn(fw).map(_.group(1).toInt)
          }.getOrElse(-1) // -1 = unknown

          val identity = ControllerIdentity(
            firmware = firmware,
            model = model,
            axisCount = axisCount,
            rawResponse = response
          )

          log.info(s"Controller identified: firmware=$firmware, model=DMC-$model, axes=$axisCount")
          lines.foreach(line => log.info(s"  ID: $line"))

          identity
        }

        val controllerIdentity = identifyController()

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
              case ex: Exception =>
                log.error(s"SendCommand failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.SendCommandResult("", error = Some(ex.getMessage))
            }
            Behaviors.same

          // Execute embedded program with automatic thread allocation and start confirmation.
          // Allocates a thread from hardware state (MG _NO), optionally sends preCommands,
          // sends XQ, then confirms via MG _NO. All steps run inside galilIo.synchronized.
          case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands) =>
            try {
              galilIo.synchronized {
                // Step 1: Allocate a thread (queries MG _NO, updates IS)
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
                        // Step 3: Send XQ command
                        val xqCmd = s"XQ #$label,$thread"
                        log.info(s"ExecuteProgram: $xqCmd")
                        val xqResponses = galilIo.send(xqCmd)

                        // Check for XQ rejection (? response)
                        val xqError = xqResponses.find { case (_, bs) =>
                          bs.utf8String.trim.startsWith("?")
                        }
                        xqError match {
                          case Some((cmd, bs)) =>
                            log.warn(s"ExecuteProgram: $xqCmd rejected: ${bs.utf8String.trim}")
                            replyTo ! GalilCommandMessage.ExecuteProgramResult(
                              thread = thread, threadWasActive = false,
                              error = Some(s"$xqCmd rejected: ${bs.utf8String.trim}"))

                          case None =>
                            // Step 4: Confirm thread started (queries MG _NO, updates IS)
                            val threadStatus = queryThreadStatus()
                            val threadBit = 1 << thread
                            val threadActive = (threadStatus & threadBit) != 0
                            log.info(s"ExecuteProgram: $xqCmd — _NO=0x${threadStatus.toHexString}, " +
                              s"thread $thread ${if threadActive then "ACTIVE" else "already finished"}")

                            replyTo ! GalilCommandMessage.ExecuteProgramResult(
                              thread = thread, threadWasActive = threadActive, error = None)
                        }
                    }
                }
              }
            } catch {
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
              case ex: Exception =>
                log.error(s"HaltExecution failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.HaltExecutionResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // GalilCommand, QRResult, DownloadProgramResult, SendCommandResult are reply-direction
          // message types that should never be sent to this actor. Log and ignore.
          case unexpected =>
            log.warn(s"ControllerCommandActor received unexpected message: $unexpected")
            Behaviors.same

        }.receiveSignal {
          case (_, org.apache.pekko.actor.typed.PostStop) =>
            log.info("ControllerCommandActor stopping — closing command connection")
            try galilIo.close() catch { case _: Exception => () }
            Behaviors.same
        }
      }
    }
}