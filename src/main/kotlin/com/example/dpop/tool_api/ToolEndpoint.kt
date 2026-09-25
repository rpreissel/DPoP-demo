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
 * A [ToolContext] that is allowed to CHANGE the journey - the only kind [ToolEndpoint.applyOutcome]
 * accepts. There are exactly two ways to obtain one, and both establish the authorization rather
 * than assume it:
 *
 * - [ToolEndpoint.beginActivation] creates the tool session it is about, so there is nothing older
 *   to be superseded by.
 * - [ToolEndpoint.loadCurrent] verifies an EXISTING session against the journey's active
 *   [com.example.dpop.orchestrator.journey.state.ToolRef] (toolId *and* toolSessionId) and throws
 *   otherwise.
 *
 * [ToolEndpoint.loadContext] deliberately returns the weaker [ToolContext]: it is the read path,
 * where a superseded session must produce a clean answer rather than a 409. Because the write path
 * needs this type, a controller cannot reach [ToolEndpoint.applyOutcome] with an unverified
 * session at all - the compiler refuses it. That replaces the older arrangement, where every
 * controller had to remember a separate `requireCurrentTool` call between loading and applying,
 * and nothing but review stopped the nineteenth one from forgetting.
 */
interface AuthorizedToolContext : ToolContext

/**
 * The API a tool controller uses instead of talking to the orchestrator directly: activation,
 * binding checks, journey transitions and the response envelope.
 *
 * Inject this into a tool controller's constructor. A typical controller:
 * 1. calls [beginActivation] (activation) or [loadCurrent] (any later write) for an
 *    [AuthorizedToolContext], or [loadContext] for the read path,
 * 2. runs its own tool-specific logic to produce a [ToolOutcome],
 * 3. calls [applyOutcome] (writes - needs the authorized context) or [buildReadResponse] (reads).
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
    fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String): AuthorizedToolContext

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
    /**
     * The write-path counterpart to [loadContext]: loads an EXISTING tool session and verifies it
     * is the one the journey currently authorizes, throwing otherwise. Returns the
     * [AuthorizedToolContext] that [applyOutcome] requires, so the check cannot be skipped by
     * forgetting a separate call.
     */
    fun loadCurrent(toolSessionId: UUID, bindingKeyRef: String, toolId: String): AuthorizedToolContext

    /** @return whether [context]'s toolId is still the journey's current tool. */
    fun isCurrentTool(context: ToolContext): Boolean

    /**
     * Abandons the currently activated tool ("Back"/"Switch"). What happens next - falling back
     * to another candidate, narrowing a mandatory offer, or ending the journey - is decided by the
     * journey's current state, not by the caller.
     */
    fun abandon(context: AuthorizedToolContext): ChannelResponse

    /**
     * Leaves the currently activated tool without declining it ("Zurück"): the journey shows its
     * selection page again, this tool still among the options.
     */
    fun back(context: AuthorizedToolContext): ChannelResponse

    /**
     * Applies a tool's [outcome] to the journey and builds the resulting response.
     *
     * Call this after running the tool's own logic, regardless of whether the outcome is
     * `InProgress`, `Failed`, or `Completed` - each is handled accordingly.
     */
    fun applyOutcome(context: AuthorizedToolContext, outcome: ToolOutcome): ChannelResponse

    /**
     * Whether [accountId] is currently locked out by the account-level brute-force throttle.
     *
     * For tools that resolve the account THEMSELVES from submitted input (LOOKUP_AUTH). A
     * IDENTIFIED_AUTH tool needs nothing here: its channel already knows the account, so
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
    fun isIdentLockedOut(personId: String?): Boolean

    /**
     * Whether a TAN/code SEND to [accountId] is currently over budget (see `SendThrottleService`).
     *
     * For LOOKUP_AUTH tools that (re-)send a TAN/code on every submission of a resolved address,
     * independent of whether any code was ever guessed wrong. Calling this COUNTS the attempt
     * against the window, so call it exactly once per resolved submission, right before deciding
     * whether to actually send.
     *
     * Same fold-it-into-the-ordinary-failure contract as [isLockedOut]: a caller over budget must
     * be handled exactly like an unresolved address, never surfaced as its own error, or this
     * becomes an account-existence oracle. `null` (nothing resolved) answers `false`: there is no
     * subject to charge, and nothing would be sent anyway.
     */
    fun isSendThrottled(accountId: Long?): Boolean

    /**
     * Whether a TAN/code SEND to the raw [contact] address (phone number or email, already
     * normalized by the caller) is currently over budget. Calling this COUNTS the attempt.
     *
     * For self-service ENROLL tools, where the caller picks a brand-new contact address for
     * themselves and no account may exist yet to key [isSendThrottled] on (see
     * `SendThrottleService`/`ThrottleScope.CONTACT_SEND`). Unlike [isSendThrottled], a throttled
     * result here may be surfaced as its own distinct failure - the caller already knows the
     * address, so there is no existence oracle to protect.
     */
    fun isSendThrottledForContact(contact: String): Boolean

    /**
     * Builds the response for a GET call.
     *
     * @param freshOutcome the tool's freshly rebuilt `InProgress` state, or `null` if [context]'s
     * tool is no longer the journey's current one (see [isCurrentTool]) - in which case the
     * response reflects the journey's actual current step instead.
     */
    fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse
}
