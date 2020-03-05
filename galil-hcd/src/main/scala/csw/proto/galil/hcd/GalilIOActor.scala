package csw.proto.galil.hcd

import java.io.IOException

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.ByteString
import csw.command.client.CommandResponseManager
import csw.framework.CurrentStatePublisher
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.Completed
import csw.params.commands.Result
import csw.params.core.models.{Id, ObsId}
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.Prefix
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}

import scala.concurrent.duration._

/**
  * Worker actor that handles the Galil I/O
  */
private[hcd] object GalilIOActor {
  // Command to publish the data record as current state
  val publishDataRecord = "publishDataRecord"

  def behavior(galilConfig: GalilConfig,
               commandResponseManager: CommandResponseManager,
               adapter: CSWDeviceAdapter,
               loggerFactory: LoggerFactory,
               galilPrefix: Prefix,
               currentStatePublisher: CurrentStatePublisher)
  : Behavior[GalilCommandMessage] =
    Behaviors.setup { ctx =>
      val log = loggerFactory.getLogger

      // Connect to Galikl device and throw error if that doesn't work
      def connectToGalil(): GalilIo = {
        try {
          GalilIoTcp(galilConfig.host, galilConfig.port)
        } catch {
          case ex: Exception =>
            log.error(
              s"Failed to connect to Galil device at ${galilConfig.host}:${galilConfig.port}")
            throw ex
        }
      }
      val galilIo = connectToGalil()

      def galilSend(cmd: String): String = {
        log.info(s"Sending '$cmd' to Galil")
        val responses = galilIo.send(cmd)
        if (responses.lengthCompare(1) != 0)
          throw new RuntimeException(
            s"Received ${responses.size} responses to Galil $cmd")
        val resp = responses.head._2.utf8String
        log.debug(s"Response from Galil: $resp")
        resp
      }


      // Check that there is a Galil device on the other end of the socket (Is there good Galil command to use here?)
      def verifyGalil(): Unit = {
        val s = galilSend("")
        if (s.nonEmpty)
          throw new IOException(
            s"Unexpected response to empty galil command: '$s': " +
              s"Check if Galil device is really located at ${galilConfig.host}:${galilConfig.port}")
      }

      verifyGalil()

      ctx.scheduleOnce(1.second, ctx.self, GalilCommand(GalilIOActor.publishDataRecord))



      // Publish the contents of the data record as a CurrentState object
      def publishDataRecord(): Unit = {
        val response = galilIo.send("QR")
        val bs = response.head._2
        val dr = DataRecord(bs)
        val cs = CurrentState(galilPrefix, StateName("DataRecord"), dr.toParamSet)
        currentStatePublisher.publish(cs)
      }

      def handleDataRecordResponse(dr: DataRecord,
                                   runId: Id,
                                   maybeObsId: Option[ObsId],
                                   cmdMapEntry: CommandMapEntry): Unit = {
        log.debug(s"handleDataRecordResponse $dr")
        val returnResponse = DataRecord.makeCommandResponse(runId, maybeObsId, dr)
        commandResponseManager.updateCommand(returnResponse)
      }

      def handleDataRecordRawResponse(bs: ByteString,
                                       runId: Id,
                                       maybeObsId: Option[ObsId],
                                       cmdMapEntry: CommandMapEntry): Unit = {
        val returnResponse = Completed(
          runId,
          new Result().add(DataRecord.key.set(bs.toByteBuffer.array())))
        commandResponseManager.updateCommand(returnResponse)
      }

      def handleGalilResponse(response: String,
                              runId: Id,
                              maybeObsId: Option[ObsId],
                              cmdMapEntry: CommandMapEntry): Unit = {
        log.debug(s"handleGalilResponse $response")
        val returnResponse = adapter.makeResponse(runId, maybeObsId, cmdMapEntry, response)
        commandResponseManager.updateCommand(returnResponse)
      }


      Behaviors.receiveMessage[GalilCommandMessage] {
        case GalilCommand(commandString) =>
          log.debug(s"doing command: $commandString")
          if (commandString == GalilIOActor.publishDataRecord) {
            publishDataRecord()
            ctx.scheduleOnce(1.second,
              ctx.self,
              GalilCommand(GalilIOActor.publishDataRecord))
          }
          Behaviors.same

        case GalilRequest(commandString, runId, maybeObsId, commandKey) =>
          log.info(s"doing command: $commandString")
          if (commandString.startsWith("QR")) {
            val response = galilIo.send(commandString)
            val bs = response.head._2
            log.debug(s"Data Record size: ${bs.size})")
            if (commandKey.name.equals("getDataRecord")) {
              // parse the data record
              val dr = DataRecord(bs)
              log.debug(s"Data Record: $dr")
              handleDataRecordResponse(dr, runId, maybeObsId, commandKey)
            } else {
              // return a paramset with the raw data record bytes
              handleDataRecordRawResponse(bs,
                runId,
                maybeObsId,
                commandKey)
            }
            Behaviors.same
          } else {
            val response = galilSend(commandString)
            handleGalilResponse(response, runId, maybeObsId, commandKey)
          }
          Behaviors.same

        case _ => log.debug("unhanded GalilCommandMessage")
          Behaviors.same
      }
    }
}

