package com.example.dpop.tool_api

import com.example.dpop.tool_spi.ToolOutcome
import java.net.URI
import java.util.UUID

/**
 * The handle a tool controller holds for one request. Obtained from [ToolEndpoint.beginActivation]
 * or [ToolEndpoint.loadContext] and passed to every other [ToolEndpoint] call afterwards - a
 * controller never needs to re-supply `toolId` or `toolSessionId` once it has one.
 */
interface ToolContext {
    /** The toolId this context was obtained for. */
    val toolId: String
    val toolSessionId: UUID
    /** The account id the current journey is working towards, or `null` if none is set yet. */
    val journeyAccountId: Long?
    /** The account id already bound to this channel, or `null` for an anonymous channel. */
    val channelAccountId: Long?
}

/**
 * The API a tool controller uses instead of talking to the orchestrator directly: activation,
 * binding checks, journey transitions and the response envelope.
 *
 * Inject this into a tool controller's constructor. A typical controller:
 * 1. calls [beginActivation] (POST) or [loadContext] (PATCH/GET) to get a [ToolContext],
 * 2. optionally calls [requireCurrentTool] to reject a stale/wrong tool session,
 * 3. runs its own tool-specific logic to produce a [ToolOutcome],
 * 4. calls [applyOutcome] to turn that outcome into a [ChannelResponse].
 */
interface ToolEndpoint {
    /**
     * Activates [toolId] for the given channel: creates a new tool session and advances the
     * journey to it.
     *
     * @param channelSessionId the channel this tool is being activated on.
     * @param bindingKeyRef the caller's resolved DPoP binding key (see [BindingKey]).
     * @throws RuntimeException if the channel/binding is invalid, or if the journey does not
     * currently offer [toolId].
     */
    fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String): ToolContext

    /**
     * Loads the context for an existing tool session, for a PATCH or GET call.
     *
     * @param toolSessionId the tool session being addressed, from the request path.
     * @param bindingKeyRef the caller's resolved DPoP binding key (see [BindingKey]).
     * @param toolId the toolId the caller expects this session to be for - use
     * [requireCurrentTool] or [isCurrentTool] afterwards to actually check it.
     * @throws RuntimeException if the tool session does not exist or the binding key does not
     * match its channel.
     */
    fun loadContext(toolSessionId: UUID, bindingKeyRef: String, toolId: String): ToolContext

    /**
     * The `Location` header value for a just-created tool resource.
     *
     * @param baseUri the scheme/host/port the client actually reached, e.g.
     * `uriBuilder.build().toUri()` from the controller's own `UriComponentsBuilder`.
     */
    fun activationLocation(context: ToolContext, baseUri: URI): URI

    /**
     * @throws RuntimeException if [context]'s toolId is not the journey's current tool.
     */
    fun requireCurrentTool(context: ToolContext)

    /** @return whether [context]'s toolId is still the journey's current tool. */
    fun isCurrentTool(context: ToolContext): Boolean

    /**
     * Abandons the currently activated tool ("Back"/"Switch"). What happens next - falling back
     * to another candidate, narrowing a mandatory offer, or ending the journey - is decided by the
     * journey's current state, not by the caller.
     */
    fun abandon(context: ToolContext): ChannelResponse

    /**
     * Applies a tool's [outcome] to the journey and builds the resulting response.
     *
     * Call this after running the tool's own logic, regardless of whether the outcome is
     * `InProgress`, `Failed`, or `Completed` - each is handled accordingly.
     */
    fun applyOutcome(context: ToolContext, outcome: ToolOutcome): ChannelResponse

    /**
     * Whether [accountId] is currently locked out by the account-level brute-force throttle.
     *
     * For tools that resolve the account THEMSELVES from submitted input (LOOKUP_AUTH). A
     * DEVICE_AUTH tool needs nothing here: its channel already knows the account, so
     * [beginActivation] checks the same throttle and rejects with an explicit 423.
     *
     * The caller must fold a `true` into its own ordinary, constant-shape failure - in practice
     * by passing `null` on to its handler, so the attempt is handled exactly like an unknown
     * e-mail. It must NOT be surfaced as a distinct error or status: a lockout that is
     * observable from outside tells an attacker which addresses have accounts, which is the very
     * thing the constant-shape failure exists to deny.
     *
     * `null` (nothing resolved) answers `false`: there is no subject to be locked.
     */
    fun isLockedOut(accountId: Long?): Boolean

    /**
     * Whether [personId] is currently locked out by the person-level IDENT throttle. Same
     * fold-it-into-the-ordinary-failure contract as [isLockedOut]; see `IdentThrottleService` for
     * why identification needs a counter of its own rather than the account one.
     */
    fun isIdentLockedOut(personId: Long?): Boolean

    /**
     * Builds the response for a GET call.
     *
     * @param freshOutcome the tool's freshly rebuilt `InProgress` state, or `null` if [context]'s
     * tool is no longer the journey's current one (see [isCurrentTool]) - in which case the
     * response reflects the journey's actual current step instead.
     */
    fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse
}
