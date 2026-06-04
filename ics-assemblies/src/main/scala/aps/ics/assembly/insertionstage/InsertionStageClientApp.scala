package aps.ics.assembly.insertionstage

import java.net.InetAddress

import org.apache.pekko.actor.typed.{ActorSystem, SpawnProtocol}
import org.apache.pekko.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.event.client.EventServiceFactory
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.Assembly
import csw.location.api.models.Connection.PekkoConnection
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.logging.client.scaladsl.LoggingSystemFactory
import csw.params.commands.Setup
import csw.params.core.models.Choice
import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import csw.prefix.models.{Prefix, Subsystem}

import aps.ics.assembly.icd.InsertionStageKeys.`ICS.STIM.InsertionStage` as IS

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

/**
 * Command-line client to exercise the Stimulus Insertion Stage assembly — the
 * assembly-side analogue of TrackInjectorApp. Resolves the assembly via the
 * Location Service and submits the SDD commands, or subscribes to its telemetry.
 *
 * Prerequisites: csw-services with the event service (`csw-services start -e`),
 * the GalilMotion HCD for controller 2, and the InsertionStage assembly running.
 *
 * Usage:
 *   InsertionStageClientApp [--assembly <name>] <command> [args]
 *
 * Commands:
 *   configure                         re-run axis configure (assembly auto-runs it at startup)
 *   home                              home the stage (PreHomed -> Operational)
 *   moveToDefault                     move to the configured default position
 *   selectSource SKY|STIMULUS         move to the sky / stimulus position
 *   positionStage ABSOLUTE|RELATIVE <mm>   absolute move / relative offset
 *   stop                              stop the stage
 *   watch                             subscribe to status + axisStatus events, print until Ctrl-C
 *
 * Options:
 *   --assembly <name>   component name under the APS subsystem
 *                       (default ICS.STIM.InsertionStage)
 *
 * Examples (assembly + HCD.2 + sim already running):
 *   sbt "ics-assemblies/runMain aps.ics.assembly.insertionstage.InsertionStageClientApp home"
 *   sbt "ics-assemblies/runMain aps.ics.assembly.insertionstage.InsertionStageClientApp selectSource STIMULUS"
 *   sbt "ics-assemblies/runMain aps.ics.assembly.insertionstage.InsertionStageClientApp positionStage RELATIVE 5"
 *   sbt "ics-assemblies/runMain aps.ics.assembly.insertionstage.InsertionStageClientApp watch"
 *
 * Tip: run `watch` in one terminal and fire commands from another to see the
 * state machine and stage position move in real time.
 */
object InsertionStageClientApp:

  // This module's application.conf is shared with the assembly, which gets its
  // networking from the CSW framework and must NOT have provider=remote forced
  // on it. A standalone client, though, needs Pekko Artery remoting to message
  // the assembly's (remote) actor ref — without it, submitAndWait resolves the
  // location over HTTP but then sends to a non-remoting system's deadLetters.
  // So enable remoting here, scoped to this client process only (same settings
  // as galil-client's application.conf), layered over the loaded config.
  private val clientConfig = ConfigFactory.parseString(
    """
      |pekko.actor.provider = remote
      |pekko.remote.artery {
      |  enabled = on
      |  transport = tcp
      |  canonical.port = 0
      |}
      |""".stripMargin
  ).withFallback(ConfigFactory.load())

  implicit val typedSystem: ActorSystem[SpawnProtocol.Command] =
    ActorSystem(SpawnProtocol(), "InsertionStageClient", clientConfig)
  implicit lazy val ec: ExecutionContextExecutor = typedSystem.executionContext
  implicit val timeout: Timeout                  = Timeout(15.seconds)

  private val locationService = HttpLocationServiceFactory.makeLocalClient
  private val source          = Prefix("CSW.InsertionStageClient")

  private val DefaultAssembly = "ICS.STIM.InsertionStage"

  def main(args: Array[String]): Unit =
    val (assemblyName, rest) = extractAssembly(args.toList)
    rest match
      case Nil =>
        printUsage(); shutdown(); sys.exit(1)
      case cmd :: cmdArgs =>
        val host = InetAddress.getLocalHost.getHostName
        LoggingSystemFactory.start("InsertionStageClient", "0.1", host, typedSystem)
        try
          cmd.toLowerCase match
            case "watch"      => watch(assemblyName)            // blocks until Ctrl-C
            case "-h" | "--help" => printUsage(); shutdown()
            case other        => submitCommand(assemblyName, other, cmdArgs)
        catch
          case e: Throwable =>
            Console.err.println(s"ERROR: ${e.getMessage}")
            shutdown(); sys.exit(1)

  // ---- arg handling ------------------------------------------------------

  /** Pull an optional `--assembly <name>` out of the args; return (name, rest). */
  private def extractAssembly(args: List[String]): (String, List[String]) =
    args match
      case "--assembly" :: name :: tail => (name, tail)
      case other                        => (DefaultAssembly, other)

  private def printUsage(): Unit =
    println("""InsertionStageClientApp — drive the Stimulus Insertion Stage assembly.
              |
              |  [--assembly <name>] <command> [args]   (default assembly: ICS.STIM.InsertionStage)
              |
              |Commands:
              |  configure
              |  home
              |  moveToDefault
              |  selectSource SKY|STIMULUS
              |  positionStage ABSOLUTE|RELATIVE <mm>
              |  stop
              |  abortErrorRecovery            halt an in-progress error recovery (only valid while recovering)
              |  watch                         subscribe to status + axisStatus (Ctrl-C to stop)
              |""".stripMargin)

  // ---- one-shot command submission --------------------------------------

  private def submitCommand(assemblyName: String, cmd: String, cmdArgs: List[String]): Unit =
    val assembly = resolveAssembly(assemblyName)
    val setup    = buildSetup(cmd, cmdArgs)
    println(s"submitting ${setup.commandName.name} to APS.$assemblyName ...")
    val resp = Await.result(assembly.submitAndWait(setup), 30.seconds)
    println(s"=> $resp")
    shutdown()

  private def resolveAssembly(name: String): CommandService =
    val prefix     = Prefix(Subsystem.APS, name)
    val connection = PekkoConnection(ComponentId(prefix, Assembly))
    Await.result(
      locationService.resolve(connection, 30.seconds).map {
        case Some(loc) => CommandServiceFactory.make(loc)
        case None      => sys.error(s"could not locate assembly '$prefix' via Location Service")
      },
      35.seconds
    )

  private def buildSetup(cmd: String, a: List[String]): Setup =
    cmd match
      case "configure"     => Setup(source, IS.ConfigureCommand.commandName, None)
      case "home"          => Setup(source, IS.HomeCommand.commandName, None)
      case "movetodefault" => Setup(source, IS.MoveToDefaultPositionCommand.commandName, None)
      case "stop"          => Setup(source, IS.StopCommand.commandName, None)
      case "aborterrorrecovery" => Setup(source, IS.AbortErrorRecoveryCommand.commandName, None)
      case "selectsource" =>
        val src = a.headOption.map(_.toUpperCase).getOrElse(sys.error("selectSource needs SKY|STIMULUS"))
        require(Set("SKY", "STIMULUS").contains(src), s"selectSource arg must be SKY or STIMULUS, got '$src'")
        Setup(source, IS.SelectSourceCommand.commandName, None)
          .add(IS.SelectSourceCommand.lightSourceKey.set(Choice(src)))
      case "positionstage" =>
        a match
          case method :: value :: Nil =>
            val m = method.toUpperCase
            require(Set("ABSOLUTE", "RELATIVE").contains(m),
                    s"positionStage method must be ABSOLUTE or RELATIVE, got '$m'")
            val mm = value.toFloatOption.getOrElse(sys.error(s"positionStage <mm> must be a number, got '$value'"))
            Setup(source, IS.PositionStageCommand.commandName, None).madd(
              IS.PositionStageCommand.positionMethodKey.set(Choice(m)),
              IS.PositionStageCommand.valueKey.set(mm)
            )
          case _ => sys.error("positionStage needs: ABSOLUTE|RELATIVE <mm>")
      case other => sys.error(s"unknown command: $other (use --help)")

  // ---- watch (telemetry subscription) -----------------------------------

  private def watch(assemblyName: String): Unit =
    val prefix       = Prefix(Subsystem.APS, assemblyName)
    val eventService = new EventServiceFactory().make(locationService)
    val subscriber   = eventService.defaultSubscriber
    val statusKey    = EventKey(prefix, EventName("status"))
    val axisKey      = EventKey(prefix, EventName("axisStatus"))

    val subscription = subscriber.subscribeCallback(Set(statusKey, axisKey), onEvent)

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      subscription.unsubscribe()
      typedSystem.terminate()
    }))

    println(s"watching APS.$assemblyName status + axisStatus events (Ctrl-C to stop) ...")
    Await.result(typedSystem.whenTerminated, Duration.Inf)

  private def onEvent(e: Event): Unit =
    e match
      case se: SystemEvent =>
        se.eventName.name match
          case "status" =>
            val as = se(IS.StatusEvent.assemblyStateKey).head.name
            val hs = se(IS.StatusEvent.hcdStateKey).head.name
            val cs = se(IS.StatusEvent.commandStateKey).head.name
            println(s"[status] assembly=$as  hcd=$hs  command=$cs")
          case "axisStatus" =>
            val st  = se(IS.AxisStatusEvent.axisStateKey).head.name
            val pos = se(IS.AxisStatusEvent.positionKey).head.toDouble
            val vel = se(IS.AxisStatusEvent.velocityKey).head.toDouble
            val idx = se(IS.AxisStatusEvent.indexedKey).head
            val inp = se(IS.AxisStatusEvent.inPositionKey).head
            println(f"[axis]   state=$st%-7s pos=$pos%8.3f mm  vel=$vel%7.3f  indexed=$idx  inPosition=$inp")
          case other =>
            println(s"[$other] $se")
      case _ => ()

  // ---- shutdown ----------------------------------------------------------

  private def shutdown(): Unit =
    typedSystem.terminate()
    Await.result(typedSystem.whenTerminated, 10.seconds)