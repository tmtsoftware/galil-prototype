package csw.proto.galil.assembly;

import akka.typed.ActorRef;
import akka.typed.javadsl.ActorContext;
import com.typesafe.config.ConfigFactory;
import csw.common.ccs.Validation;
import csw.common.ccs.Validations;
import csw.common.framework.internal.wiring.FrameworkWiring;
import csw.common.framework.internal.wiring.Standalone;
import csw.common.framework.javadsl.JComponentBehaviorFactory;
import csw.common.framework.javadsl.JComponentHandlers;
import csw.common.framework.models.*;
import csw.param.states.CurrentState;
import csw.services.location.commons.ClusterAwareSettings;
import csw.services.location.scaladsl.ActorSystemFactory;
import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JComponentLogger;
import csw.services.logging.scaladsl.LoggingSystemFactory;
import scala.runtime.BoxedUnit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;

public class JGalilAssembly {

  // Base trait for Galil Assembly domain messages
  interface JGalilAssemblyDomainMessage extends RunningMessage.DomainMessage {
  }
  // Add messages here...


  interface JGalilAssemblyLogger extends JComponentLogger {
    @Override
    default String componentName() {
      return "GalilAssembly";
    }
  }

  @SuppressWarnings("unused")
  public static class JGalilAssemblyBehaviorFactory extends JComponentBehaviorFactory<JGalilAssemblyDomainMessage> {

    public JGalilAssemblyBehaviorFactory() {
      super(JGalilAssembly.JGalilAssemblyDomainMessage.class);
    }

    @Override
    public JComponentHandlers<JGalilAssemblyDomainMessage> make(ActorContext<ComponentMessage> ctx,
                                                                ComponentInfo componentInfo,
                                                                ActorRef<PubSub.PublisherMessage<CurrentState>> pubSubRef) {
      return new JGalilAssembly.JGalilAssemblyHandlers(ctx, componentInfo, pubSubRef, JGalilAssemblyDomainMessage.class);
    }
  }

  static class JGalilAssemblyHandlers extends JComponentHandlers<JGalilAssemblyDomainMessage> implements JGalilAssemblyLogger {
    // XXX Can't this be done in the interface?
    private ILogger log = getLogger();

    JGalilAssemblyHandlers(ActorContext<ComponentMessage> ctx,
                           ComponentInfo componentInfo,
                           ActorRef<PubSub.PublisherMessage<CurrentState>> pubSubRef,
                           Class<JGalilAssemblyDomainMessage> klass) {
      super(ctx, componentInfo, pubSubRef, klass);
      log.debug("Starting Galil Assembly");
    }

    private BoxedUnit doNothing() {
      return null;
    }

    @Override
    public CompletableFuture<BoxedUnit> jInitialize() {
      log.debug("jInitialize called");
      return CompletableFuture.supplyAsync(this::doNothing);
    }

    @Override
    public void onDomainMsg(JGalilAssemblyDomainMessage galilAssemblyDomainMessage) {
      log.debug("onDomainMessage called: " + galilAssemblyDomainMessage);
    }

    @Override
    public Validation onControlCommand(CommandMessage commandMsg) {
      log.debug("onControlCommand called: " + commandMsg);
      return Validations.JValid();
    }

    @Override
    public CompletableFuture<BoxedUnit> jOnShutdown() {
      log.debug("onShutdown called");
      return CompletableFuture.supplyAsync(this::doNothing);
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

  public static void main(String[] args) throws UnknownHostException {
    String host = InetAddress.getLocalHost().getHostName();
//    akka.actor.ActorSystem system = akka.actor.ActorSystem.create();
    akka.actor.ActorSystem system = ClusterAwareSettings.system();
    LoggingSystemFactory.start("GalilAssembly", "0.1", host, system);
    FrameworkWiring wiring = FrameworkWiring.make(system);
    Standalone.spawn(ConfigFactory.load("GalilAssembly.conf"), wiring);
  }
}
