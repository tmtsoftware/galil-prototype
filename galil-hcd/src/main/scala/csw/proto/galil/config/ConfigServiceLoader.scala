package csw.proto.galil.config

import com.typesafe.config.{Config, ConfigFactory}
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.scaladsl.LocationService
import org.apache.pekko.actor.typed.ActorSystem

import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * Loads a component's Typesafe [[Config]], preferring the CSW Configuration
 * Service (the *active* version at `csPath`) and falling back to a bundled
 * classpath resource when the service is unreachable or the path is not yet
 * present.
 *
 * Two reasons this exists:
 *   - It removes the JVM-global config selection (a `-D` system property), so
 *     several components can share one container, each resolving its own path
 *     from its own identity.
 *   - The fallback keeps local / offline development and tests working without a
 *     running Config Service — the fallback call is the same `ConfigFactory.load`
 *     the callers used before, so the offline path is unchanged.
 *
 * `csw-config-client` (which provides [[ConfigClientFactory]]) is already on the
 * classpath transitively via `csw-framework`.
 */
object ConfigServiceLoader:

  /** The parsed config plus a human description of where it came from (for logs). */
  final case class Loaded(config: Config, source: String)

  /**
   * @param csPath           Config Service path, e.g. "galil/GalilHcdConfig-APS-2.conf"
   * @param fallbackResource bundled resource basename handed to `ConfigFactory.load`
   *                         (kept identical to each caller's previous argument so the
   *                         offline path is byte-for-byte unchanged)
   * @param timeout          bounds both the active-version fetch and the parse, so a
   *                         missing Config Service can't stall `initialize()`
   */
  def load(
      csPath: String,
      fallbackResource: String,
      locationService: LocationService,
      system: ActorSystem[?],
      timeout: FiniteDuration = 10.seconds
  ): Loaded =
    try
      val client = ConfigClientFactory.clientApi(system, locationService)
      Await.result(client.getActive(Paths.get(csPath)), timeout) match
        case Some(data) =>
          Loaded(Await.result(data.toConfigObject(using system), timeout), s"Config Service [$csPath]")
        case None =>
          Loaded(ConfigFactory.load(fallbackResource), s"bundled resource [$fallbackResource] (absent in Config Service)")
    catch
      case NonFatal(e) =>
        Loaded(ConfigFactory.load(fallbackResource), s"bundled resource [$fallbackResource] (Config Service unavailable: ${e.getMessage})")
