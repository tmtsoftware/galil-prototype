package csw.proto.galil.assembly;

import akka.typed.ActorRef;
import akka.typed.Props;
import akka.typed.javadsl.ActorContext;
import akka.util.Timeout;
import csw.common.ccs.Validation;
import csw.common.ccs.Validations;
import csw.common.framework.javadsl.JAssemblyInfoFactory;
import csw.common.framework.javadsl.JComponentHandlers;
import csw.common.framework.javadsl.JComponentWiring;
import csw.common.framework.models.*;
import csw.common.framework.scaladsl.SupervisorBehaviorFactory;
import csw.param.states.CurrentState;
import csw.services.location.models.ComponentId;
import csw.services.location.models.Connection;
import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JComponentLogger;
import csw.services.logging.scaladsl.LoggingSystemFactory;
import scala.runtime.BoxedUnit;
import scala.runtime.Nothing$;
import csw.services.location.models.Connection.AkkaConnection;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static csw.services.location.javadsl.JComponentType.HCD;
import static csw.services.location.javadsl.JConnectionType.AkkaType;

public class JGalilAssembly {

  // Base trait for Galil Assembly domain messages
  interface JGalilAssemblyDomainMsg extends RunningMsg.DomainMsg {
  }
  // Add messages here...


  interface JGalilAssemblyLogger extends JComponentLogger {
    @Override
    default String componentName() {
      return "GalilAssembly";
    }
  }

  @SuppressWarnings("unused")
  public static class JGalilAssemblyWiring extends JComponentWiring<JGalilAssemblyDomainMsg> {

    public JGalilAssemblyWiring() {
      super(JGalilAssembly.JGalilAssemblyDomainMsg.class);
    }

    @Override
    public JComponentHandlers<JGalilAssemblyDomainMsg> make(ActorContext<ComponentMsg> ctx, ComponentInfo componentInfo, ActorRef<PubSub.PublisherMsg<CurrentState>> pubSubRef) {
      return new JGalilAssembly.JGalilAssemblyHandlers(ctx, componentInfo, JGalilAssemblyDomainMsg.class);
    }
  }

  static class JGalilAssemblyHandlers extends JComponentHandlers<JGalilAssemblyDomainMsg> implements JGalilAssemblyLogger {
    // XXX Can't this be done in the interface?
    private ILogger log = getLogger();

    public JGalilAssemblyHandlers(ActorContext<ComponentMsg> ctx, ComponentInfo componentInfo, Class<JGalilAssemblyDomainMsg> klass) {
      super(ctx, componentInfo, klass);
      log.debug("Starting Galil Assembly");
    }

    private BoxedUnit init() {
      log.debug("Initialize called");
      return null;
    }

    @Override
    public CompletableFuture<BoxedUnit> jInitialize() {
      return CompletableFuture.supplyAsync(this::init);
    }


    @Override
    public void onRun() {
      log.debug("OnRun called");
    }

    @Override
    public void onDomainMsg(JGalilAssemblyDomainMsg galilAssemblyDomainMsg) {
      log.debug("onDomainMsg called: " + galilAssemblyDomainMsg);
    }

    @Override
    public Validation onControlCommand(CommandMsg commandMsg) {
      log.debug("onControlCommand called: " + commandMsg);
      return Validations.JValid(); // XXX Need Java API for this
    }

    @Override
    public void onShutdown() {
      log.debug("onShutdown called");
    }

    @Override
    public void onRestart() {
      log.debug("onRestart called");
    }

    @Override
    public void onGoOffline() {
      log.debug("onGoOffline called");
    }

    @Override
    public void onGoOnline() {
      log.debug("onGoOnline called");
    }
  }

  private static void startLogging() throws UnknownHostException {
    String host = InetAddress.getLocalHost().getHostName();
    akka.actor.ActorSystem system = akka.actor.ActorSystem.create();
    LoggingSystemFactory.start("GalilAssembly", "0.1", host, system);

    // XXX: How to log here?
//    log.debug("Starting Galil Assembly");
  }

  private static void startAssembly() {
    ComponentInfo.AssemblyInfo assemblyInfo = JAssemblyInfoFactory.make("GalilAssembly",
        "wfos",
        "csw.proto.galil.assembly.JGalilAssembly$JGalilAssemblyWiring",
        LocationServiceUsages.JRegisterAndTrackServices(),
        Collections.singleton(AkkaType),
        Collections.singleton(new AkkaConnection(new ComponentId("GalilHcd", HCD))));

    akka.typed.ActorSystem system = akka.typed.ActorSystem.create(akka.typed.scaladsl.Actor.empty(), "GalilAssembly");
    Timeout timeout = Timeout.apply(2, TimeUnit.SECONDS);
    // A component developer will never have to create an actor as they will only create and test handlers. In java we could use Void if need be.
    system.<Void>systemActorOf(SupervisorBehaviorFactory.make(assemblyInfo), "GalilAssemblySupervisor", Props.empty(), timeout);
  }

  public static void main(String[] args) throws UnknownHostException {
    startLogging();
    startAssembly();
  }
}
