package csw.proto.galil.assembly;

import akka.typed.ActorRef;
import akka.typed.Props;
import akka.typed.javadsl.ActorContext;
import akka.util.Timeout;
import csw.common.ccs.Validation;
import csw.common.framework.javadsl.JComponentHandlers;
import csw.common.framework.javadsl.JComponentWiring;
import csw.common.framework.models.*;
import csw.common.framework.scaladsl.SupervisorBehaviorFactory;
import csw.param.states.CurrentState;
import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JComponentLogger;
import csw.services.logging.scaladsl.LoggingSystemFactory;
import scala.reflect.ClassTag;
import scala.runtime.BoxedUnit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import scala.runtime.Nothing$;

import static csw.common.framework.models.JComponent.RegisterOnly;

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
    // XXX FIXME
    private ClassTag<JGalilAssemblyDomainMsg> classTag = scala.reflect.ClassTag$.MODULE$.apply(JGalilAssembly.JGalilAssemblyDomainMsg.class);

    public JGalilAssemblyWiring() {
      super(JGalilAssembly.JGalilAssemblyDomainMsg.class);
    }

    @Override
    public JComponentHandlers<JGalilAssemblyDomainMsg> make(ActorContext<ComponentMsg> ctx, Component.ComponentInfo componentInfo, ActorRef<PubSub.PublisherMsg<CurrentState>> pubSubRef) {
      return new JGalilAssembly.JGalilAssemblyHandlers(ctx, componentInfo, classTag);
    }
  }

  static class JGalilAssemblyHandlers extends JComponentHandlers<JGalilAssemblyDomainMsg> implements JGalilAssemblyLogger {
    // XXX Can't this be done in the interface?
    private ILogger log = getLogger();

    JGalilAssemblyHandlers(ActorContext<ComponentMsg> ctx, Component.ComponentInfo componentInfo, ClassTag<JGalilAssemblyDomainMsg> classTag) {
      super(ctx, componentInfo, classTag);
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
    public Validation.Validation onControlCommand(CommandMsg commandMsg) {
      log.debug("onControlCommand called: " + commandMsg);
      return Validation.Valid$.MODULE$; // XXX Need Java API for this
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
    Component.AssemblyInfo assemblyInfo = new Component.AssemblyInfo("GalilAssembly",
        "wfos",
        "csw.proto.galil.assembly.JGalilAssembly$JGalilAssemblyWiring",
        RegisterOnly,
        null, // XXX Set(AkkaType) - No easy Java API yet, Scala Set required...
        null); // XXX Set(AkkaConnection(ComponentId("GalilHcd", HCD))

    akka.typed.ActorSystem system = akka.typed.ActorSystem.create("GalilAssembly", akka.typed.scaladsl.Actor.empty());
    Timeout timeout = Timeout.apply(2, TimeUnit.SECONDS);
    // XXX What is the correct syntax here?
    system.<Nothing$>systemActorOf(SupervisorBehaviorFactory.make(assemblyInfo), "GalilAssemblySupervisor", Props.empty(), timeout);
  }

  public static void main(String[] args) throws UnknownHostException {
    startLogging();
    startAssembly();
  }
}
