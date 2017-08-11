package csw.proto.galil.hcd;

import akka.typed.ActorRef;
import akka.typed.Props;
import akka.typed.javadsl.ActorContext;
import akka.util.Timeout;
import csw.common.ccs.Validation;
import csw.common.ccs.Validations;
import csw.common.framework.javadsl.JComponentHandlers;
import csw.common.framework.javadsl.JComponentWiring;
import csw.common.framework.javadsl.JHcdInfoFactory;
import csw.common.framework.models.*;
import csw.common.framework.scaladsl.SupervisorBehaviorFactory;
import csw.param.states.CurrentState;
import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JComponentLogger;
import csw.services.logging.scaladsl.LoggingSystemFactory;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static csw.services.location.javadsl.JConnectionType.AkkaType;

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

    public JGalilHcdWiring() {
      super(JGalilHcd.JGalilHcdDomainMsg.class);
    }

    @Override
    public JComponentHandlers<JGalilHcdDomainMsg> make(ActorContext<ComponentMsg> ctx, ComponentInfo componentInfo, ActorRef<PubSub.PublisherMsg<CurrentState>> pubSubRef) {
      return new JGalilHcd.JGalilHcdHandlers(ctx, componentInfo, JGalilHcd.JGalilHcdDomainMsg.class);
    }
  }

  static class JGalilHcdHandlers extends JComponentHandlers<JGalilHcdDomainMsg> implements JGalilHcdLogger {
    // XXX Can't this be done in the interface?
    private ILogger log = getLogger();

    public JGalilHcdHandlers(ActorContext<ComponentMsg> ctx, ComponentInfo componentInfo, Class<JGalilHcdDomainMsg> klass) {
      super(ctx, componentInfo, klass);
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
    public Validation onControlCommand(CommandMsg commandMsg) {
      log.debug("onControlCommand called: " + commandMsg);
      return Validations.JValid();
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
    ComponentInfo.HcdInfo hcdInfo = JHcdInfoFactory.make("GalilHcd",
        "wfos",
        "csw.proto.galil.hcd.JGalilHcd$JGalilHcdWiring",
        LocationServiceUsages.JRegisterOnly(),
        Collections.singleton(AkkaType),
        FiniteDuration.create(5, TimeUnit.SECONDS));

    akka.typed.ActorSystem system = akka.typed.ActorSystem.create(akka.typed.scaladsl.Actor.empty(), "GalilHcd");
    Timeout timeout = Timeout.apply(2, TimeUnit.SECONDS);
    // A component developer will never have to create an actor as they will only create and test handlers. In java we could use Void if need be.
    system.<Void>systemActorOf(SupervisorBehaviorFactory.make(hcdInfo), "GalilHcdSupervisor", Props.empty(), timeout);
  }

  public static void main(String[] args) throws UnknownHostException {
    startLogging();
    startHcd();
  }
}
