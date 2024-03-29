package csw.proto.galil.hcd

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands._
import csw.params.core.models.{Id, ObsId}
import csw.prefix.models.Subsystem.CSW
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.time.core.models.UTCTime

import scala.concurrent.ExecutionContextExecutor

// Add messages here...
sealed trait GalilCommandMessage

object GalilCommandMessage {

  case class GalilCommand(commandString: String) extends GalilCommandMessage

  case class GalilRequest(commandString: String, runId: Id, maybeObsId: Option[ObsId], cmdMapEntry: CommandMapEntry)
      extends GalilCommandMessage

}

private class GalilHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._

  private val log                           = loggerFactory.getLogger
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val config                        = ConfigFactory.load("GalilCommands.conf")
  private val adapter                       = new CSWDeviceAdapter(config)
  private val galilIoActor: ActorRef[GalilCommandMessage] =
    ctx.spawn(
      GalilIOActor
        .behavior(
          getGalilConfig,
          commandResponseManager,
          adapter,
          loggerFactory,
          componentInfo.prefix,
          cswCtx.currentStatePublisher
        ),
      "GalilIOActor"
    )

  override def initialize(): Unit = log.debug("Initialize called")

  override def onShutdown(): Unit = log.debug("onShutdown called")

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.debug(s"validateSubmit called: $controlCommand")
    controlCommand match {
      case x: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(x)
        if (cmdMapEntry.isSuccess) {
          val cmdString = adapter.validateSetup(x, cmdMapEntry.get)
          if (cmdString.isSuccess) {
            CommandResponse.Accepted(runId)
          }
          else {
            CommandResponse.Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(cmdString.failed.get.getMessage))
          }
        }
        else {
          CommandResponse.Invalid(runId, CommandIssue.OtherIssue(cmdMapEntry.failed.get.getMessage))
        }
      case _: Observe =>
        CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue("Observe not supported"))
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.debug(s"onSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(setup)
        val cmdString   = adapter.validateSetup(setup, cmdMapEntry.get)
        galilIoActor ! GalilRequest(cmdString.get, runId, setup.maybeObsId, cmdMapEntry.get)
        CommandResponse.Started(runId)
      case x =>
        // Should not happen after validation
        CommandResponse.Error(runId, s"Unexpected submit: $x")
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(setup)
        val cmdString   = adapter.validateSetup(setup, cmdMapEntry.get)
        cmdString.get match {
          case cmd if cmd == GalilIOActor.publishDataRecord =>
            galilIoActor ! GalilCommand(cmd)
          case cmd =>
            galilIoActor ! GalilRequest(cmd, runId, setup.maybeObsId, cmdMapEntry.get)
        }
      case _ => // Only Setups handled
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  def getGalilConfig: GalilConfig = {
    val config = ctx.system.settings.config
    val host =
      if (config.hasPath("galil.host")) config.getString("galil.host")
      else "127.0.0.1"
    val port =
      if (config.hasPath("galil.port")) config.getInt("galil.port") else 8888
    log.info(s"Galil host = $host, port = $port")
    GalilConfig(host, port)
  }

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

object GalilHcdApp extends App {
  val defaultConfig = ConfigFactory.load("GalilHcd.conf")
  ContainerCmd.start("galil.hcd.GalilHcd", CSW, args, Some(defaultConfig))
}
