package csw.proto.galil.hcd

import java.io.IOException

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import com.typesafe.config.Config
import csw.command.client.CommandResponseManager
import csw.framework.CurrentStatePublisher
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.commands.Result
import csw.params.core.models.{Id, ObsId}
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.Prefix
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Worker actor that handles the Galil I/O
 * 
 * Note: GetQR and QRResult are defined in GalilHcd.scala as part of GalilCommandMessage
 */
private[hcd] object ControllerInterfaceActor {

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
      config: Config,
      commandResponseManager: CommandResponseManager,
      adapter: CSWDeviceAdapter,
      loggerFactory: LoggerFactory,
      galilPrefix: Prefix,
      currentStatePublisher: CurrentStatePublisher,
      internalStateActor: ActorRef[InternalStateActor.Command],
      simulate: Boolean = false
  ): Behavior[GalilCommandMessage] =
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val log = loggerFactory.getLogger

        // Connect to Galil device and throw error if that doesn't work
        def connectToGalil(): GalilIo = {
          try {
            GalilIoTcp(galilConfig.host, galilConfig.port)
          }
          catch {
            case ex: Exception =>
              log.error(s"Failed to connect to Galil device at ${galilConfig.host}:${galilConfig.port}")
              throw ex
          }
        }
        val galilIo = connectToGalil()

        // Spawn ControllerConsoleActor as child — opens a dedicated second TCP handle
        // to receive unsolicited MG output from embedded programs. Skipped in simulation
        // mode (simulator has no second handle or CF/CW routing infrastructure).
        //
        // The latch blocks this Behaviors.setup block until CF I + CW 2 complete.
        // GalilHcd.initialize() blocks on GetIdentity after ctx.spawn(CI actor), so
        // the wait is inline in the init chain — #Init will not run until the console
        // handle is live and capturing MG output.
        if !simulate then
          val consoleLatch = new java.util.concurrent.CountDownLatch(1)
          ctx.spawn(
            ControllerConsoleActor(
              host       = galilConfig.host,
              port       = galilConfig.port,
              prefix     = galilPrefix,
              log        = log,
              readyLatch = consoleLatch
            ),
            "ControllerConsoleActor"
          )
          val ready = consoleLatch.await(ControllerConsoleActor.ReadyTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
          if !ready then
            log.warn("ControllerConsoleActor did not become ready within timeout — proceeding without MG capture")

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

        def handleDataRecordResponse(dr: DataRecord, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.debug(s"handleDataRecordResponse $dr")
          val returnResponse = DataRecord.makeCommandResponse(runId, maybeObsId, dr)
          commandResponseManager.updateCommand(returnResponse)
        }

        def handleDataRecordRawResponse(
            bs: ByteString,
            runId: Id,
            maybeObsId: Option[ObsId],
            cmdMapEntry: CommandMapEntry
        ): Unit = {
          val returnResponse = Completed(runId, new Result().add(DataRecord.key.set(bs.toByteBuffer.array())))
          commandResponseManager.updateCommand(returnResponse)
        }

        def handleUploadProgram(filename: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.info(s"Uploading program from file: $filename")
          
          // NOTE: StatusMonitor QR polling is paused by GalilHcd before this is called
          
          try {
            // Small delay to let any in-flight QR command complete
            Thread.sleep(100)
            
            ProgramFileManager.readProgramFile(filename, config) match {
              case Success(programText) =>
                // Prepare program for upload (strip comments, etc.)
                val cleanedProgram = ProgramFileManager.prepareProgramForUpload(programText)
                log.info(s"Prepared program: ${cleanedProgram.length} characters")
                
                try {
                  log.info(s"Program size: ${cleanedProgram.length} characters")
                  
                  // Entire upload sequence must be synchronized
                  galilIo.synchronized {
                    // Step 0: Halt any running programs
                    log.info("Halting programs (HX)")
                    val hxResponses = galilIo.send("HX")
                    log.info(s"HX got: ${hxResponses.map(_._2.utf8String).mkString}")
                    
                    // Step 1: Enter DL mode
                    log.info("Entering DL mode (using writeRaw)")
                    galilIo.writeRaw("DL")
                    
                    // Step 2: Stream program data
                    log.info(s"Streaming ${cleanedProgram.length} characters of program data")
                    galilIo.writeRaw(cleanedProgram)
                    
                    // Step 3: Send terminator
                    log.info("Sending terminator")
                    val termResponses = galilIo.send("\\")
                    val termResponse = termResponses.map(_._2.utf8String).mkString.trim
                    log.info(s"Terminator response: '$termResponse'")
                    
                    if (termResponse.nonEmpty && !termResponse.contains(":")) {
                      throw new RuntimeException(s"Upload terminator failed: $termResponse")
                    }
                    
                    // DIAGNOSTIC: Check for residual data
                    log.debug("DIAGNOSTIC: Checking for residual data after DL")
                    val residual = galilIo.drainAndShowBuffer()
                    if (residual.nonEmpty) {
                      log.warn(s"DIAGNOSTIC: Found ${residual.length} bytes of residual data!")
                      log.warn(s"DIAGNOSTIC: First 200 chars: ${residual.take(200)}")
                    } else {
                      log.debug("DIAGNOSTIC: Buffer is clean after DL")
                    }
                  }
                  
                  log.info(s"Successfully uploaded program from $filename")
                  commandResponseManager.updateCommand(Completed(runId, new Result()))
                } catch {
                  case ex: Exception =>
                    log.error(s"Failed to upload program: ${ex.getMessage}")
                    commandResponseManager.updateCommand(
                      Error(runId, s"Upload failed: ${ex.getMessage}")
                    )
                }
                
              case Failure(ex) =>
                log.error(s"Failed to read program file $filename: ${ex.getMessage}")
                commandResponseManager.updateCommand(
                  Error(runId, s"Failed to read program file: ${ex.getMessage}")
                )
            }
          } finally {
            // NOTE: StatusMonitor QR polling is resumed by GalilHcd after this completes
            Thread.sleep(100)
          }
        }

        def handledownloadProgram(filename: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.info(s"Downloading programs to file: $filename")
          
          // NOTE: StatusMonitor QR polling is paused by GalilHcd before this is called
          
          try {
            // Small delay to let any in-flight QR complete
            Thread.sleep(100)
            
            log.info("Sending UL command to controller")
            val responses = galilIo.synchronized {
              log.debug("DIAGNOSTIC: Checking buffer state before UL")
              val preUL = galilIo.drainAndShowBuffer()
              if (preUL.nonEmpty) {
                if (preUL.length == 1 && preUL == ":") {
                  log.debug("DIAGNOSTIC: Found controller prompt (':') - this is normal")
                } else {
                  log.warn(s"DIAGNOSTIC: Found ${preUL.length} bytes BEFORE UL command!")
                }
              }
              
              galilIo.send("UL")
            }
            log.info(s"Received ${responses.size} response chunks from UL")
            
            val allText = responses.map(_._2.utf8String).mkString
            log.info(s"Total response: ${allText.length} characters")
            
            val cleanedProgram = allText
              .stripSuffix("\\")
              .stripSuffix("\u001A")
              .trim
            
            log.info(s"Cleaned program: ${cleanedProgram.length} characters")
            
            ProgramFileManager.writeProgramFile(filename, cleanedProgram, config) match {
              case Success(filePath) =>
                log.info(s"Successfully downloaded programs to $filePath")
                commandResponseManager.updateCommand(
                  Completed(runId, new Result().add(CSWDeviceAdapter.filenameKey.set(filePath)))
                )
                
              case Failure(ex) =>
                log.error(s"Failed to write program file: ${ex.getMessage}")
                commandResponseManager.updateCommand(
                  Error(runId, s"Failed to write program file: ${ex.getMessage}")
                )
            }
          } catch {
            case ex: Exception =>
              log.error(s"Failed to download programs: ${ex.getMessage}")
              commandResponseManager.updateCommand(
                Error(runId, s"Download failed: ${ex.getMessage}")
              )
          } finally {
            // NOTE: StatusMonitor QR polling is resumed by GalilHcd after this completes
            Thread.sleep(100)
          }
        }

        def handleGalilResponse(response: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry): Unit = {
          log.debug(s"handleGalilResponse $response")
          val returnResponse = adapter.makeResponse(runId, maybeObsId, cmdMapEntry, response)
          commandResponseManager.updateCommand(returnResponse)
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
          // Allocates a thread from hardware state (MG _NO), sends XQ, then confirms.
          case GalilCommandMessage.ExecuteProgram(label, replyTo) =>
            try {
              galilIo.synchronized {
                // Step 1: Allocate a thread (queries MG _NO, updates IS)
                allocateThread() match {
                  case None =>
                    replyTo ! GalilCommandMessage.ExecuteProgramResult(
                      thread = -1, threadWasActive = false,
                      error = Some(s"No threads available to execute #$label"))

                  case Some(thread) =>
                    // Step 2: Send XQ command
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
                        // Step 3: Confirm thread started (queries MG _NO, updates IS)
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
            } catch {
              case ex: Exception =>
                log.error(s"ExecuteProgram failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = -1, threadWasActive = false,
                  error = Some(s"ExecuteProgram failed: ${ex.getMessage}"))
            }
            Behaviors.same

          // Halt an active execution thread and stop the axis motor (SDD 4.8.1).
          //
          // The thread parameter comes from IS.activeThread (set by CH when the program
          // started). CI performs a hardware confirmation via MG _NO before sending HX:
          // if the thread bit is no longer set, the program already finished and HX is
          // skipped (ST is still sent to ensure the motor is stopped).
          //
          // This avoids relying solely on IS.activeThread, which can be transiently
          // stale if a QR poll or CommandWatcher update races with this call.
          case GalilCommandMessage.HaltExecution(thread, axis, replyTo) =>
            try {
              galilIo.synchronized {
                // Confirm thread is still active in hardware before sending HX
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
                    else
                      log.info(s"HaltExecution: thread $thread halted")
                  else
                    log.info(s"HaltExecution: thread $thread already finished (_NO=0x${threadStatus.toHexString}), skipping HX")

                // Always send ST to stop any residual motor motion on the axis
                val stCmd = s"ST ${axis.char}"
                log.info(s"HaltExecution: stopping motor with $stCmd")
                val stResponses = galilIo.send(stCmd)
                val stError = stResponses.find { case (_, bs) =>
                  bs.utf8String.trim.startsWith("?")
                }
                if stError.isDefined then
                  val errMsg = s"ST ${axis.char} rejected: ${stError.get._2.utf8String.trim}"
                  log.error(s"HaltExecution: $errMsg")
                  replyTo ! GalilCommandMessage.HaltExecutionResult(success = false, error = Some(errMsg))
                else
                  log.info(s"HaltExecution: axis ${axis.char} stopped successfully")
                  replyTo ! GalilCommandMessage.HaltExecutionResult(success = true)
              }
            } catch {
              case ex: Exception =>
                log.error(s"HaltExecution failed: ${ex.getMessage}")
                replyTo ! GalilCommandMessage.HaltExecutionResult(success = false, error = Some(ex.getMessage))
            }
            Behaviors.same

          // GetQR handler for StatusMonitor integration
          case GalilCommandMessage.GetQR(replyTo) =>
            try {
              // Synchronized access to prevent interference with file operations
              val response = galilIo.synchronized {
                galilIo.send("QR")
              }
              val bs = response.head._2
              val dr = DataRecord(bs)
              replyTo ! GalilCommandMessage.QRResult(dr)
            } catch {
              case ex: Exception =>
                log.error(s"QR command failed: ${ex.getMessage}")
                // Don't send reply on error - StatusMonitor will timeout and retry
            }
            Behaviors.same

          case GalilRequest(commandString, runId, maybeObsId, commandKey, setup) =>
            log.info(s"doing command: $commandString")
            
            // Check for special commands that need file handling
            commandKey.name match {
              case "uploadProgram" =>
                setup.get(CSWDeviceAdapter.filenameKey) match {
                  case Some(param) =>
                    val filename = param.head
                    handleUploadProgram(filename, runId, maybeObsId, commandKey)
                  case None =>
                    log.error("uploadProgram command missing filename parameter")
                    commandResponseManager.updateCommand(
                      Error(runId, "Missing filename parameter")
                    )
                }
                Behaviors.same
                
              case "downloadProgram" =>
                setup.get(CSWDeviceAdapter.filenameKey) match {
                  case Some(param) =>
                    val filename = param.head
                    handledownloadProgram(filename, runId, maybeObsId, commandKey)
                  case None =>
                    log.error("downloadProgram command missing filename parameter")
                    commandResponseManager.updateCommand(
                      Error(runId, "Missing filename parameter")
                    )
                }
                Behaviors.same
                
              case "getDataRecord" | "getDataRecordRaw" if commandString.startsWith("QR") =>
                val response = galilIo.send(commandString)
                val bs       = response.head._2
                log.debug(s"Data Record size: ${bs.size})")
                if (commandKey.name.equals("getDataRecord")) {
                  val dr = DataRecord(bs)
                  log.debug(s"Data Record: $dr")
                  handleDataRecordResponse(dr, runId, maybeObsId, commandKey)
                }
                else {
                  handleDataRecordRawResponse(bs, runId, maybeObsId, commandKey)
                }
                Behaviors.same
                
              case _ =>
                val response = galilSend(commandString)
                handleGalilResponse(response, runId, maybeObsId, commandKey)
                Behaviors.same
            }
            
          case _ =>
            // Handle other GalilCommandMessage types if needed
            Behaviors.same
        }
      }
    }
}