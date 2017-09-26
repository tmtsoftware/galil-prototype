package csw.proto.galil.assembly;

import akka.typed.ActorRef;
import akka.typed.javadsl.ActorContext;
import com.typesafe.config.ConfigFactory;
import csw.framework.internal.wiring.FrameworkWiring;
import csw.framework.internal.wiring.Standalone;
import csw.framework.javadsl.JComponentBehaviorFactory;
import csw.framework.javadsl.JComponentHandlers;
import csw.framework.models.ComponentInfo;
import csw.param.messages.*;
import csw.param.models.Validation;
import csw.param.models.Validations;
import csw.param.states.CurrentState;
import csw.services.location.commons.ClusterAwareSettings;
import csw.services.location.javadsl.ILocationService;
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

  @SuppressWarnings("unused")
  public static class JGalilAssemblyBehaviorFactory extends JComponentBehaviorFactory<JGalilAssemblyDomainMessage> {

    public JGalilAssemblyBehaviorFactory() {
      super(JGalilAssembly.JGalilAssemblyDomainMessage.class);
    }

    @Override
    public JComponentHandlers<JGalilAssemblyDomainMessage> jHandlers(
        ActorContext<ComponentMessage> ctx,
        ComponentInfo componentInfo,
        ActorRef<PubSub.PublisherMessage<CurrentState>> pubSubRef,
        ILocationService locationService) {
      return new JGalilAssembly.JGalilAssemblyHandlers(ctx, componentInfo, pubSubRef, locationService,
          JGalilAssemblyDomainMessage.class);
    }
  }

  static class JGalilAssemblyHandlers extends JComponentHandlers<JGalilAssemblyDomainMessage>
      implements JComponentLogger {
    private ILogger log = getLogger();

    JGalilAssemblyHandlers(ActorContext<ComponentMessage> ctx,
                           ComponentInfo componentInfo,
                           ActorRef<PubSub.PublisherMessage<CurrentState>> pubSubRef,
                           ILocationService locationService,
                           Class<JGalilAssemblyDomainMessage> klass) {
      super(ctx, componentInfo, pubSubRef, locationService, klass);
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
    public CompletableFuture<BoxedUnit> jOnRun() {
      log.debug("jOnRun called");
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
    public void onCommandValidationNotification(CommandValidationResponse validationResponse) {
      log.debug("onCommandValidationNotification called: " + validationResponse);
    }

    @Override
    public void onCommandExecutionNotification(CommandExecutionResponse executionResponse) {
      log.debug("onCommandExecutionNotification called: " + executionResponse);
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

    @Override
    public String componentName() {
      return "GalilAssembly";
    }
  }

  public static void main(String[] args) throws UnknownHostException {
    String host = InetAddress.getLocalHost().getHostName();
    akka.actor.ActorSystem system = ClusterAwareSettings.system();
    LoggingSystemFactory.start("GalilAssembly", "0.1", host, system);
    FrameworkWiring wiring = FrameworkWiring.make(system);
    Standalone.spawn(ConfigFactory.load("GalilAssembly.conf"), wiring);
  }
}
