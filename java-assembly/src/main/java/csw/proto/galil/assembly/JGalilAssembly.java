package csw.proto.galil.assembly;

import akka.typed.ActorRef;
import akka.typed.javadsl.ActorContext;
import com.typesafe.config.ConfigFactory;
import csw.common.ccs.Validation;
import csw.common.ccs.Validations;
import csw.common.framework.javadsl.JComponentHandlers;
import csw.common.framework.javadsl.JComponentWiring;
import csw.common.framework.models.*;
import csw.common.framework.scaladsl.Component$;
import csw.param.states.CurrentState;
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
  public static class JGalilAssemblyWiring extends JComponentWiring<JGalilAssemblyDomainMessage> {

    public JGalilAssemblyWiring() {
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
    public void onDomainMsg(JGalilAssemblyDomainMessage galilAssemblyDomainMessage) {
      log.debug("onDomainMessage called: " + galilAssemblyDomainMessage);
    }

    @Override
    public Validation onControlCommand(CommandMessage commandMsg) {
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
    LoggingSystemFactory.start("GalilAssembly", "0.1", host, system);

    // XXX: How to log here?
//    log.debug("Starting Galil Assembly");
  }

  private static void startAssembly() {
    // XXX TODO: Java API not implemented yet!
    Component$.MODULE$.createStandalone(ConfigFactory.load("GalilAssembly.conf"));
  }

  public static void main(String[] args) throws UnknownHostException {
    startLogging();
    startAssembly();
  }
}
