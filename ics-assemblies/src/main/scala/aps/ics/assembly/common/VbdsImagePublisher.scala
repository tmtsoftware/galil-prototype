package aps.ics.assembly.common

import org.apache.pekko.Done
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.Multipart.FormData
import csw.logging.api.scaladsl.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Real VBDS image publisher (SDD §5.1.2.2.1 / §5.1.6.5). POSTs a FITS-encoded
 * frame to a named VBDS stream over the transfer HTTP route. Replaces the
 * logging [[StubImagePublisher]] for the APT acquisition/guiding detector — the
 * sole VBDS publisher (PIT/PSH store to disk/DMS and keep the stub for now).
 * Built as a reusable `common` piece: PIT/PSH can adopt VBDS later (e.g. for the
 * sequencer ICS simulator) by constructing this + calling [[ensureStream]], with
 * no change here.
 *
 * The vbds-server is located by CONFIGURED host/port (per the standing decision:
 * config, not the Location Service — the CSW-6/Pekko VBDS runs its own cluster).
 * The wire contract, verified against esw-vbds@angelic/csw6 source:
 *   - create stream: POST /vbds/admin/streams/{stream}?contentType={ct}
 *       -> 200 OK (created) | 409 Conflict (already exists) — both mean "ready"
 *   - publish frame: POST /vbds/transfer/streams/{stream}
 *       multipart/form-data, file part named "data" WITH a filename (server does
 *       fileUpload("data"), which only matches parts whose filename.isDefined)
 *       -> 202 Accepted | 400 Bad Request (stream does not exist, OR the "data"
 *          part is missing/has no filename so fileUpload finds nothing)
 * The server appends a one-byte newline terminator per file (stream framing); the
 * FITS bytes we send are the file payload.
 *
 * @param host        vbds-server HTTP host (config `vbds.host`)
 * @param port        vbds-server HTTP port (config `vbds.port`)
 * @param stream      VBDS stream name to create/publish to (config `vbds.stream`)
 * @param contentType stream content-type metadata at creation (config `vbds.contentType`)
 * @param system      the assembly's actor system (HTTP client + materializer)
 * @param log         CSW logger
 */
final class VbdsImagePublisher(
    host: String,
    port: Int,
    stream: String,
    contentType: String,
    system: ActorSystem[?],
    log: Logger
) extends DetectorImagePublisher:

  private given ClassicActorSystemProvider = system
  private given ExecutionContext           = system.executionContext

  private val adminUri = s"http://$host:$port/vbds/admin/streams/$stream"

  override def kind: String = "vbds"

  /**
   * Idempotently create the stream so subsequent publishes are accepted. 200 and
   * 409 both resolve as ready; any other status (or a connection failure — the
   * Future fails) is surfaced so the caller can stay FAULTED and retry.
   */
  def ensureStream(): Future[Done] =
    val uri = if contentType.nonEmpty then s"$adminUri?contentType=$contentType" else adminUri
    Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri)).flatMap { resp =>
      resp.discardEntityBytes()
      resp.status match
        case StatusCodes.OK | StatusCodes.Conflict =>
          log.info(s"[VBDS] stream '$stream' ready at $host:$port (${resp.status.intValue})")
          Future.successful(Done)
        case other =>
          Future.failed(new RuntimeException(s"VBDS create stream '$stream' failed: $other"))
    }

  /** FITS-encode the frame and POST it to the transfer route. Fire-and-forget at
   *  the call site (the base ignores the Future), so failures are logged here. */
  override def publish(streamName: String, frame: Frame): Future[Done] =
    val bytes    = FitsEncoder.encode(frame)
    // The part MUST carry a filename: the server matches it with fileUpload("data"),
    // which only selects body parts where filename.isDefined (mirrors the reference
    // client's FormData.BodyPart.fromPath, which sets filename from the file name).
    // A part with name="data" but no filename is silently skipped -> 400. The value
    // is server-local (not transmitted to subscribers); it only satisfies the directive.
    val bodyPart = FormData.BodyPart(
      "data",
      HttpEntity(ContentTypes.`application/octet-stream`, bytes),
      Map("filename" -> s"$streamName.fits")
    )
    val transfer = s"http://$host:$port/vbds/transfer/streams/$streamName"
    val result =
      Marshal(FormData(bodyPart)).to[RequestEntity].flatMap { entity =>
        Http()
          .singleRequest(HttpRequest(method = HttpMethods.POST, uri = transfer, entity = entity))
          .flatMap { resp =>
            resp.discardEntityBytes()
            resp.status match
              case StatusCodes.Accepted => Future.successful(Done)
              case other                => Future.failed(new RuntimeException(s"status $other"))
          }
      }
    result.andThen {
      case Failure(ex) => log.error(s"[VBDS] publish to '$streamName' failed: ${ex.getMessage}")
      case Success(_)  => ()
    }