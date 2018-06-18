package csw.proto.galil.hcd

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import com.typesafe.config.{Config, ConfigFactory}
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers, CurrentStatePublisher}
import csw.messages.commands._
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.params.models.{Id, ObsId, Prefix}
import csw.messages.scaladsl.TopLevelActorMessage
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.hcd.StatePollerMessage.StartMessage
import csw.services.command.scaladsl.CommandResponseManager
import csw.services.event.scaladsl.EventService
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.LoggerFactory

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Add messages here...
sealed trait GalilCommandMessage

object GalilCommandMessage {

  case class GalilCommand(commandString: String) extends GalilCommandMessage

  case class GalilRequest(commandString: String, prefix: Prefix, runId: Id, maybeObsId: Option[ObsId],
                          cmdMapEntry: CommandMapEntry) extends GalilCommandMessage

}

private class GalilHcdBehaviorFactory extends ComponentBehaviorFactory {
  override def handlers(ctx: ActorContext[TopLevelActorMessage],
                        componentInfo: ComponentInfo,
                        commandResponseManager: CommandResponseManager,
                        currentStatePublisher: CurrentStatePublisher,
                        locationService: LocationService,
                        eventService: EventService,
                        loggerFactory: LoggerFactory
                       ): ComponentHandlers =
    new GalilHcdHandlers(ctx, componentInfo, commandResponseManager, currentStatePublisher, locationService, eventService, loggerFactory)
}


private class GalilHcdHandlers(ctx: ActorContext[TopLevelActorMessage],
                               componentInfo: ComponentInfo,
                               commandResponseManager: CommandResponseManager,
                               currentStatePublisher: CurrentStatePublisher,
                               locationService: LocationService,
                               eventService: EventService,
                               loggerFactory: LoggerFactory)
  extends ComponentHandlers(ctx, componentInfo, commandResponseManager, currentStatePublisher,
    locationService, eventService, loggerFactory) {

  private val log = loggerFactory.getLogger
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val config = ConfigFactory.load("GalilCommands.conf")
  private val adapter = new CSWDeviceAdapter(config)
  private val galilConfig = getGalilConfig
  private val galilHardwareActor: ActorRef[GalilCommandMessage] = ctx.spawnAnonymous(
    GalilIOActor.behavior(getGalilConfig, commandResponseManager, adapter, loggerFactory))


  // read in poller flag from environment
  val systemConfig: Config = ctx.system.settings.config
  val pollerEnabled: Boolean = if (systemConfig.hasPath("galil.hcd.pollerEnabled")) systemConfig.getBoolean("galil.hcd.pollerEnabled") else false

  log.debug(s"pollerEnabled = $pollerEnabled")

  if (pollerEnabled) {
    val statePublisherActor: ActorRef[StatePollerMessage] =
      ctx.spawnAnonymous(StatePollerActor.behavior(galilConfig, componentInfo, currentStatePublisher, loggerFactory))

    log.debug(s"sending start message to state publisher: $statePublisherActor")
    statePublisherActor ! StartMessage()
  }

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def validateCommand(controlCommand: ControlCommand): CommandResponse = {
    log.debug(s"validateSubmit called: $controlCommand")
    controlCommand match {
      case x: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(x)
        if (cmdMapEntry.isSuccess) {
          val cmdString = adapter.validateSetup(x, cmdMapEntry.get)
          if (cmdString.isSuccess) {
            CommandResponse.Accepted(controlCommand.runId)
          } else {
            CommandResponse.Invalid(controlCommand.runId, CommandIssue.ParameterValueOutOfRangeIssue(cmdString.failed.get.getMessage))
          }
        } else {
          CommandResponse.Invalid(controlCommand.runId, CommandIssue.OtherIssue(cmdMapEntry.failed.get.getMessage))
        }
      case _: Observe =>
        CommandResponse.Invalid(controlCommand.runId, CommandIssue.UnsupportedCommandIssue("Observe not supported"))
    }
  }

  override def onSubmit(controlCommand: ControlCommand): Unit = {
    log.debug(s"onSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(setup)
        if (cmdMapEntry.isSuccess) {
          val cmdString = adapter.validateSetup(setup, cmdMapEntry.get)
          if (cmdString.isSuccess) {
            galilHardwareActor ! GalilRequest(cmdString.get, setup.source, setup.runId, setup.maybeObsId, cmdMapEntry.get)
          }
        }
      case _ => // Only Setups handled
    }
  }

  override def onOneway(controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val cmdMapEntry = adapter.getCommandMapEntry(setup)
        if (cmdMapEntry.isSuccess) {
          val cmdString = adapter.validateSetup(setup, cmdMapEntry.get)
          if (cmdString.isSuccess) {
            galilHardwareActor ! GalilCommand(cmdString.get)
          }
        }
      case _ => // Only Setups handled
    }
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  def getGalilConfig: GalilConfig = {
    val config = ctx.system.settings.config
    val host = if (config.hasPath("galil.host")) config.getString("galil.host") else "127.0.0.1"
    val port = if (config.hasPath("galil.port")) config.getInt("galil.port") else 8888
    log.info(s"Galil host = $host, port = $port")
    GalilConfig(host, port)
  }

}

object GalilHcdApp extends App {
  val defaultConfig = ConfigFactory.load("GalilHcd.conf")
  ContainerCmd.start("GalilHcd", args, Some(defaultConfig))
}