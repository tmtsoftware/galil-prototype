package csw.proto.galil.hcd.hmi

import org.apache.pekko.actor.typed.ActorSystem
import csw.logging.client.appenders.{LogAppender, LogAppenderBuilder}
import play.api.libs.json.JsObject

import scala.concurrent.Future

/**
 * CSW log appender that feeds every HCD log message to the HMI WebSocket stream.
 *
 * Registered in application.conf alongside FileAppender and StdOutAppender:
 *
 *   csw-logging.appenders = [
 *     "csw.logging.client.appenders.FileAppender$",
 *     "csw.logging.client.appenders.StdOutAppender$",
 *     "csw.proto.galil.hcd.hmi.HmiLogAppender$"
 *   ]
 *
 * The CSW framework instantiates this class via reflection, so it cannot
 * receive the WebSocket broadcast callback through its constructor. Instead,
 * HmiServer sets HmiLogAppender.broadcast during start() — a controlled
 * companion-object var, justified by the framework's reflection constraint.
 *
 * Each log message is forwarded as a "logLine" JSON WebSocket frame:
 *   { "type": "logLine", "severity": "INFO", "timestamp": "...",
 *     "message": "...", "component": "GalilHcd" }
 *
 * The frontend renders these in a unified log panel alongside state updates.
 * Controller MG output (from ControllerConsoleActor) appears here automatically
 * since it is emitted via log.info("[GALIL:prefix] text") — no separate console
 * stream needed.
 *
 * Level filtering is performed in append() against the minimum level set via
 * HmiLogAppender.minSeverity. The HMI exposes this as a dropdown, allowing
 * runtime control without restarting the HCD. This is separate from the CSW
 * Admin API log level control (which affects what the logger dispatches at all).
 *
 * Thread safety: broadcast and minSeverity are @volatile; append() is called
 * from the CSW LogActor thread.
 */
object HmiLogAppender extends LogAppenderBuilder {

  /** Severity ordering — used for minSeverity filtering in append(). */
  private val severityOrder: Map[String, Int] = Map(
    "TRACE" -> 0,
    "DEBUG" -> 1,
    "INFO"  -> 2,
    "WARN"  -> 3,
    "ERROR" -> 4,
    "FATAL" -> 5
  )

  /**
   * Callback set by HmiServer.start() once the WebSocket infrastructure is live.
   * Each call pushes a "logLine" JSON string to all connected WebSocket clients.
   * Null until HmiServer wires it in (messages before that are silently dropped).
   */
  @volatile var broadcast: String => Unit = null

  /** Called by HmiServer.stop() — prevents pushing to a torn-down WebSocket. */
  def clearBroadcast(): Unit = broadcast = null

  /**
   * Minimum severity for HMI display. Messages below this level are dropped
   * before reaching the WebSocket, keeping the HMI panel readable.
   *
   * Default: INFO — filters out DEBUG/TRACE noise in normal operation.
   * Set by the HMI log-level dropdown (does NOT affect FileAppender output).
   */
  @volatile var minSeverity: String = "INFO"

  /** Called by the CSW framework to construct the appender instance. */
  override def apply(system: ActorSystem[?], stdHeaders: JsObject): LogAppender =
    new HmiLogAppender(stdHeaders)
}

class HmiLogAppender(stdHeaders: JsObject) extends LogAppender {
  import HmiLogAppender._

  override def append(baseMsg: JsObject, category: String): Unit = {
    val bc = broadcast
    if (bc == null) return  // HmiServer not yet started — drop silently

    // Extract fields from the CSW log JsObject
    val severity  = (baseMsg \ "@severity").asOpt[String].getOrElse("INFO").toUpperCase
    val timestamp = (baseMsg \ "timestamp").asOpt[String].getOrElse("")
    val message   = (baseMsg \ "message").asOpt[String].getOrElse(
                      (baseMsg \ "msg").asOpt[String].getOrElse("")
                    )

    // "actor" = full Pekko actor path (Serialization.serializedActorPath format).
    // Walk backwards past noise segments to find the last meaningful actor name.
    //
    // Noise predicates (all observed empirically):
    //   starts with "$"         — Pekko synthetic refs ($$a-adapter, $a, etc.)
    //   contains "#"            — actor incarnation suffixes (actorName#12345)
    //   equals "user"           — Pekko guardian segment
    //   empty                   — leading slash artifact
    //
    // CSW structural segments (contain ".") are handled separately:
    //   ends with "-component-actor"  → "ComponentActor"  (GalilHcdHandlers runs here)
    //   otherwise (just the prefix)   → "Supervisor"      (CSW lifecycle messages)
    //
    // Console output: ControllerConsoleActor emits MG lines via log.info with the message
    // text matching "[prefix] ..." — these technically show ControllerInterfaceActor as the
    // actor path (shared logger context), but we relabel them "Console" to reflect true origin.
    val rawActor: String = (baseMsg \ "actor").asOpt[String] match {
      case Some(path) =>
        val segments = path.split("/").toList.reverse
        // Check the deepest segment first for CSW structural names (contain ".")
        segments.headOption.filter(_.contains(".")) match {
          case Some(seg) if seg.endsWith("-component-actor") => "ComponentActor"
          case Some(_)                                       => "Supervisor"
          case None =>
            // Walk backwards past synthetic/noise segments
            segments
              .dropWhile(s => s.startsWith("$") || s.contains("#") || s == "user" || s.isEmpty)
              .headOption.getOrElse("Console")
        }
      case None =>
        // No actor field — should not occur now that all actors use getLogger(ctx).
        "Unknown"
    }

    // Override: ControllerConsoleActor MG lines are logged via ControllerInterfaceActor's
    // logger context, but their message always starts with "[prefix] " — relabel as "Console"
    // to reflect the true origin (embedded Galil program MG output).
    val actor = if (message.matches("""\[[\w.:]+\] .*""")) "Console" else rawActor

    // Apply HMI-level severity filter (independent of CSW logger level)
    val msgOrder = severityOrder.getOrElse(severity, 2)
    val minOrder = severityOrder.getOrElse(minSeverity, 2)
    if (msgOrder < minOrder) return

    val json = HmiJsonProtocol.logLineToJson(severity, timestamp, message, actor)
    try { bc(json) } catch { case _: Exception => /* never crash the log pipeline */ }
  }

  override def finish(): Future[Unit] = Future.successful(())
  override def stop(): Future[Unit]   = Future.successful(())
}