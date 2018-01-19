package csw.proto.galil.hcd

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages.CommandResponseManagerMessage.AddOrUpdateCommand
import csw.messages.RunningMessage.DomainMessage
import csw.messages._
import csw.messages.ccs.CommandIssue
import csw.messages.ccs.commands._
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.models.PubSub.PublisherMessage
import csw.messages.params.models.{ObsId, Prefix, RunId}
import csw.messages.params.states.CurrentState
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.{GalilCommand, GalilRequest}
import csw.proto.galil.hcd.GalilResponseMessage.GalilResponse
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.LoggerFactory

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilHcdDomainMessage extends DomainMessage

// Add messages here...
sealed trait GalilCommandMessage extends GalilHcdDomainMessage

object GalilCommandMessage {

  case class GalilCommand(commandString: String) extends GalilCommandMessage

  case class GalilRequest(commandString: String, prefix: Prefix, runId: RunId, maybeObsId: Option[ObsId],
                           cmdMapEntry: CommandMapEntry) extends GalilCommandMessage

}

sealed trait GalilResponseMessage extends GalilHcdDomainMessage

object GalilResponseMessage {

  case class GalilResponse(response: String, prefix: Prefix, runId: RunId, maybeObsId: Option[ObsId],
                           cmdMapEntry: CommandMapEntry) extends GalilResponseMessage

}


private class GalilHcdBehaviorFactory extends ComponentBehaviorFactory[GalilHcdDomainMessage] {
  override def handlers(ctx: ActorContext[TopLevelActorMessage],
                        componentInfo: ComponentInfo,
                        commandResponseManager: ActorRef[CommandResponseManagerMessage],
                        pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                        locationService: LocationService,
                        loggerFactory: LoggerFactory
                       ): ComponentHandlers[GalilHcdDomainMessage] =
    new GalilHcdHandlers(ctx, componentInfo, commandResponseManager, pubSubRef, locationService, loggerFactory)
}


private class GalilHcdHandlers(ctx: ActorContext[TopLevelActorMessage],
                               componentInfo: ComponentInfo,
                               commandResponseManager: ActorRef[CommandResponseManagerMessage],
                               pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                               locationService: LocationService,
                               loggerFactory: LoggerFactory)
  extends ComponentHandlers[GalilHcdDomainMessage](ctx, componentInfo, commandResponseManager, pubSubRef,
    locationService, loggerFactory) {

  private val log = loggerFactory.getLogger
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private[this] val config = ConfigFactory.load("GalilCommands.conf")
  private val adapter = new CSWDeviceAdapter(config)

  private var galilHardwareActor: ActorRef[GalilCommandMessage] = _

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
    galilHardwareActor = ctx.spawnAnonymous(GalilIOActor.behavior(getGalilConfig, Some(ctx.self), loggerFactory))
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMsg: GalilHcdDomainMessage): Unit = galilMsg match {
    case x: GalilResponseMessage => handleGalilResponse(x)

    case x => log.debug(s"onDomainMessage called: $x")
  }

  def handleGalilResponse(galilResponseMessage: GalilResponseMessage): Unit = {
    log.debug(s"handleGalilResponse $galilResponseMessage")
    galilResponseMessage match {
      case GalilResponse(response, prefix, runId, maybeObsId, cmdMapEntry) =>
        val returnResponse = adapter.makeResponse(prefix, runId, maybeObsId, cmdMapEntry, response)
        commandResponseManager ! AddOrUpdateCommand(returnResponse.runId, returnResponse)
    }
  }

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
      case _ =>
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
      case _ =>
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