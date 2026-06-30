package aps.ics.assembly.common

import csw.logging.api.scaladsl.Logger
import org.apache.pekko.Done

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future

/**
 * The image-publishing seam (SDD §5.1.2.2.1 / §5.1.6.5 — the assembly assembles
 * the corrected frame and publishes it over VBDS for the acquisition/guiding
 * path). Isolating it behind a trait keeps the VBDS prototype dependency
 * (tmtsoftware/esw-vbds) out of the build until it is confirmed to resolve: this
 * cut ships only the logging [[StubImagePublisher]]. A future
 * `VbdsImagePublisher` — resolving the vbds-server via the Location Service and
 * POSTing the frame bytes to a named stream — drops in behind the same trait
 * with NO call-site change in the assemblies.
 */
trait DetectorImagePublisher:
  /** Publish one frame to `stream`. Completes when the post is accepted. */
  def publish(stream: String, frame: Frame): Future[Done]

  /** Short label for telemetry/logging ("stub", "vbds", ...). */
  def kind: String

/**
 * No-op VBDS stand-in: logs the stream, frame dimensions, byte size and a
 * running published-frame counter, then completes immediately. This lets the
 * full exposure → correction → "publish" → metrics path run end-to-end (and the
 * UI show a frame counter and "VBDS: stubbed") without any network or
 * esw-vbds dependency.
 */
final class StubImagePublisher(log: Logger) extends DetectorImagePublisher:
  private val count = new AtomicLong(0L)

  override def kind: String = "stub"

  override def publish(stream: String, frame: Frame): Future[Done] =
    val n = count.incrementAndGet()
    log.info(
      s"[VBDS stub] publish frame #$n to stream '$stream' " +
        s"(${frame.width}x${frame.height}, ${frame.sizeBytes} bytes) — not transmitted"
    )
    Future.successful(Done)

  /** Frames "published" so far (surfaced as telemetry by the assembly). */
  def published: Long = count.get()
