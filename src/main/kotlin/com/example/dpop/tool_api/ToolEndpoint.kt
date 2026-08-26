package com.example.dpop.tool_api

import com.example.dpop.tool_spi.ToolOutcome
import java.net.URI
import java.util.UUID

/**
 * The flat handle a tool controller actually needs (docs/04-orchestrierung.md #5). A controller
 * never reads more than these four scalars off its context - checked against all twelve
 * controllers before this interface was cut. It deliberately does NOT carry the real
 * AuthJourney/ChannelSession/ToolSession: those are orchestrator-internal, and a method module
 * must never see them, only the account ids and the running tool session's id.
 *
 * [toolId] is the caller's own asserted toolId (a compile-time constant in every tool controller,
 * a `@PathVariable` in [ToolSwitchController]) - set once at [ToolEndpoint.beginActivation]/
 * [ToolEndpoint.loadContext] instead of being re-passed to every subsequent [ToolEndpoint] call.
 */
interface ToolContext {
    val toolId: String
    val toolSessionId: UUID
    val journeyAccountId: Long?
    val channelAccountId: Long?
}

/**
 * The one thing a tool controller talks to instead of the orchestrator directly
 * (docs/04-orchestrierung.md #5) - binding check, journey-offer validation, retry/throttle
 * bookkeeping and the response envelope, all centralized so no controller can silently diverge.
 *
 * Implemented by the orchestrator (`ToolControllerSupport`); a method module only ever sees this
 * interface plus [ToolContext] - never a concrete orchestrator type. What is deliberately NOT
 * here: any knowledge of which tool may run when. That is a question about the journey's current
 * state, which the implementation answers - a controller only finds out by trying.
 *
 * Every method beyond the two that construct a [ToolContext] takes `(context, [one domain
 * object])` - never a bare `toolId`/`toolSessionId` again, since the caller already put both into
 * the context it is holding.
 */
interface ToolEndpoint {
    /** Mints the ToolSession and rejects [toolId] if the journey does not currently offer it. */
    fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String): ToolContext

    fun loadContext(toolSessionId: UUID, bindingKeyRef: String, toolId: String): ToolContext

    /**
     * The `Location` of a just-created tool resource - identical across every tool's activate().
     * [baseUri] is the scheme/host/port the client actually reached (`uriBuilder.build().toUri()`
     * in the controller) - the path itself is fixed and owned by the implementation, never by the
     * caller, which is why this takes a plain `java.net.URI` rather than a Spring
     * `UriComponentsBuilder`: this SPI must not require a method module to depend on Spring Web.
     */
    fun activationLocation(context: ToolContext, baseUri: URI): URI

    fun requireCurrentTool(context: ToolContext)

    fun isCurrentTool(context: ToolContext): Boolean

    /**
     * "Back"/"Switch": abandon the currently activated tool. What happens then is entirely the
     * journey's decision - moving along a fallback chain, narrowing a mandatory offer, or ending
     * like a cancel once nothing is left. The caller only decides that there IS an abandonment;
     * it never touches the journey to make that decision itself, which is why this - and not just
     * [applyOutcome] - is part of the SPI: it is the one effect that is not a tool outcome at all.
     */
    fun abandon(context: ToolContext): ChannelResponse

    /** InProgress/Failed/Completed -> journey transition + the response envelope. */
    fun applyOutcome(context: ToolContext, outcome: ToolOutcome): ChannelResponse

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown. */
    fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse
}
