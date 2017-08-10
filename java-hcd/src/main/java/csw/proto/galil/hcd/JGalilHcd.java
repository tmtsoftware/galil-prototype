package csw.proto.galil.hcd;

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
import scala.concurrent.duration.FiniteDuration;
import scala.reflect.ClassTag;
import scala.runtime.BoxedUnit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import csw.common.framework.models.Component.ComponentInfo;
import scala.runtime.Nothing$;

import static csw.common.framework.models.JComponent.DoNotRegister;

public class JGalilHcd {

  // Base trait for Galil HCD domain messages
  interface JGalilHcdDomainMsg extends RunningMsg.DomainMsg {
  }
  // Add messages here...


  interface JGalilHcdLogger extends JComponentLogger {
    @Override
    default String componentName() {
      return "GalilHcd";
    }
  }

  @SuppressWarnings("unused")
  public static class JGalilHcdWiring extends JComponentWiring<JGalilHcdDomainMsg> {
    // XXX FIXME
    private ClassTag<JGalilHcd.JGalilHcdDomainMsg> classTag = scala.reflect.ClassTag$.MODULE$.apply(JGalilHcd.JGalilHcdDomainMsg.class);

    public JGalilHcdWiring() {
      super(JGalilHcd.JGalilHcdDomainMsg.class);
    }

    @Override
    public JComponentHandlers<JGalilHcd.JGalilHcdDomainMsg> make(ActorContext<ComponentMsg> ctx, Component.ComponentInfo componentInfo, ActorRef<PubSub.PublisherMsg<CurrentState>> pubSubRef) {
      return new JGalilHcd.JGalilHcdHandlers(ctx, componentInfo, classTag);
    }
  }

  static class JGalilHcdHandlers extends JComponentHandlers<JGalilHcdDomainMsg> implements JGalilHcdLogger {
    // XXX Can't this be done in the interface?
    private ILogger log = getLogger();

    JGalilHcdHandlers(ActorContext<ComponentMsg> ctx, ComponentInfo componentInfo, ClassTag<JGalilHcdDomainMsg> classTag) {
      super(ctx, componentInfo, classTag);
      log.debug("Starting Galil HCD");
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
    public void onDomainMsg(JGalilHcdDomainMsg galilHcdDomainMsg) {
      log.debug("onDomainMsg called: " + galilHcdDomainMsg);
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
    LoggingSystemFactory.start("GalilHcd", "0.1", host, system);

    // XXX: How to log here?
//    log.debug("Starting Galil HCD");
  }

  private static void startHcd() {
    Component.HcdInfo hcdInfo = new Component.HcdInfo("trombone",
        "wfos",
        "csw.proto.galil.hcd.JGalilHcd$JGalilHcdWiring",
        DoNotRegister,
        null, // XXX Set(AkkaType) - No easy Java API yet, Scala Set required...
        FiniteDuration.create(5, TimeUnit.SECONDS));

    akka.typed.ActorSystem system = akka.typed.ActorSystem.create("GalilHcd", akka.typed.scaladsl.Actor.empty());
    Timeout timeout = Timeout.apply(2, TimeUnit.SECONDS);
    // XXX What is the correct syntax here?
    system.<Nothing$>systemActorOf(SupervisorBehaviorFactory.make(hcdInfo), "GalilHcdSupervisor", Props.empty(), timeout);
  }

  public static void main(String[] args) throws UnknownHostException {
    startLogging();
    startHcd();
  }
}
