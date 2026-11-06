package ironmcp
package otel
package auth

import cats.Monad
import cats.syntax.all.*
import ironmcp.auth.{AuthAudit, AuthEvent}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer

/** Authorization decisions as OpenTelemetry signals:
  *
  *   - a counter, `mcp.auth.decisions`, labelled by `mcp.auth.allowed`,
  *     `mcp.auth.reason` and `mcp.method.name`: alert on a rise in
  *     `audience` or `signature` refusals, which mean someone is presenting
  *     tokens that were never meant for this server;
  *   - an event, `mcp.auth.decision`, on the current span, with the subject,
  *     client, target and scopes, so a trace shows why a request was refused.
  *
  * Neither carries the token. Combine with a durable log with
  * [[AuthAudit.both]].
  */
object OtelAuth:

  val Decisions = "mcp.auth.decisions"

  def audit[F[_]: Monad](meter: Meter[F], tracer: Tracer[F]): F[AuthAudit[F]] =
    meter
      .counter[Long](Decisions)
      .withUnit("{decision}")
      .withDescription("Authorization decisions at the MCP resource server")
      .create
      .map { counter => (event: AuthEvent) =>
        val labels = List(
          Attribute("mcp.auth.allowed", event.allowed),
          Attribute("mcp.auth.reason", event.reason),
          Attribute(McpSemantics.Attributes.MethodName, event.method)
        )
        val details = labels ++ List(
          Some(Attribute("mcp.auth.target", event.target)),
          event.subject.map(Attribute("enduser.id", _)),
          event.clientId.map(Attribute("mcp.auth.client_id", _)),
          event.issuer.map(Attribute("mcp.auth.issuer", _)),
          event.jkt.map(Attribute("mcp.auth.dpop_jkt", _)),
          Option.when(event.required.nonEmpty)(Attribute("mcp.auth.scopes.required", event.required.toList.sorted: Seq[String])),
          Option.when(event.granted.nonEmpty)(Attribute("mcp.auth.scopes.granted", event.granted.toList.sorted: Seq[String]))
        ).flatten
        counter.add(1, labels*) *> tracer.currentSpanOrNoop.flatMap(_.addEvent("mcp.auth.decision", details*))
      }
