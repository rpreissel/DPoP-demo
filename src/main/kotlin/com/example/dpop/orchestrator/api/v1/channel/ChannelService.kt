package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.account.AccountService
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.journey.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelCreationThrottleService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.TokenService
import com.example.dpop.tool_api.ActiveMethodView
import com.example.dpop.tool_api.ChannelBlock
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.Next
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The channel-level entry points. Everything about WHICH tool comes next belongs to the journey
 * ([JourneyService]) - this class only decides which intent a request means and hands the result
 * back in the one response envelope.
 */
@Service
@Transactional
class ChannelService(
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val authContextService: AuthContextService,
    private val authPolicy: AuthPolicy,
    private val channelAccessGuard: ChannelAccessGuard,
    private val journeyService: JourneyService,
    private val tokenService: TokenService,
    private val channelCreationThrottleService: ChannelCreationThrottleService
) {

    /**
     * Always mints a brand-new ChannelSession - DPoP proves which device this is, but is
     * deliberately never a lookup key for resuming a session (docs/02-domaenenmodell.md #3). The
     * client must remember `channelSessionId` and call [getChannel] to resume.
     *
     * [intent] picks the strategy for this channel and is REMEMBERED on it: resume and cancel
     * restart the same one. Only FAST consults the durable [DeviceAccountLink] - REGISTER and
     * LOGIN_LOOKUP both mean "not the account this device already knows".
     */
    fun initializeChannel(
        bindingKeyRef: String,
        requestedAcrFloor: String?,
        intent: String? = null,
        availableTools: List<String> = emptyList()
    ): ChannelResponse {
        // Before anything is created: this endpoint is unauthenticated (a self-signed DPoP proof
        // costs nothing) and every fresh channel resets AuthJourney.attemptBudget, so without a
        // limit here every per-journey budget in the system is a formality.
        channelCreationThrottleService.recordAndAssertWithinBudget(bindingKeyRef)

        val entryIntent = AuthIntent.fromRequest(intent)
            ?: throw OrchestratorException.invalidState("Unbekannter intent: $intent")
        if (!entryIntent.isEntryIntent) {
            throw OrchestratorException.invalidState("$entryIntent kann keinen Kanal eroeffnen")
        }

        val linkedAccountId = if (entryIntent == AuthIntent.FAST_ACCESS) {
            sessionManagementService.findLinkedAccountId(bindingKeyRef)
        } else {
            null
        }
        val channel = sessionManagementService
            .createChannelSession(bindingKeyRef, ChannelSession.Channel.APP, CHANNEL_TTL, linkedAccountId)
        channel.entryIntent = entryIntent
        // Fixed for the channel's whole lifetime (docs/03-tool-architektur.md, availability) - never
        // updated again, unlike the backend-wide kill-switch which is read live on every step.
        channel.availableClientTools = availableTools.toMutableSet()
        sessionManagementService.updateChannelSession(channel)
        requestedAcrFloor?.let { sessionManagementService.raiseChannelAcrFloor(channel.channelSessionId!!, it) }

        return resumeChannel(sessionManagementService.findChannelSessionById(channel.channelSessionId!!)!!)
    }

    /** The guaranteed resume entry point (docs/05-api.md #2): re-derives the currently due `next`. */
    fun getChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse =
        resumeChannel(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))

    /**
     * Same data as ChannelResponse.activeMethods, addressable as its own resource (docs/05-api.md
     * #2). Empty, not an error, when no evidence has been produced yet for this channel
     * ([ChannelSession.hasProvenFactor]) - including when a device was merely recognized
     * (`accountId` already set via `DeviceAccountLink`) but never actually proven anything here.
     */
    fun getMethods(channelSessionId: UUID, bindingKeyRef: String): MethodsResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val methods = if (channel.hasProvenFactor) {
            channel.accountId?.let { accountService.findAccount(it)?.activeAuthenticationMethods }
        } else {
            null
        }
        return MethodsResponse(toActiveMethodViews(methods))
    }

    /**
     * The mock Keycloak AccessToken (docs/11-umsetzungsplan.md: real Keycloak facade out of
     * scope). Covers both first issuance and refresh - [minValiditySeconds] is the caller's
     * tolerance, the backend alone decides whether the existing token still qualifies or a new
     * one gets minted (via the remembered RefreshToken, never exposed here).
     */
    fun getToken(channelSessionId: UUID, bindingKeyRef: String, minValiditySeconds: Long): TokenResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        requireAuthenticated(channel)
        val pair = tokenService.tokenFor(channel.authContextId!!, minValiditySeconds)
        return TokenResponse(accessToken = pair.accessToken, accessExpiresAt = pair.accessExpiresAt, refreshExpiresAt = pair.refreshExpiresAt)
    }

    /** The fachliche (business) ID-token claims - a separate resource from the AccessToken's own claims. */
    fun getIdClaims(channelSessionId: UUID, bindingKeyRef: String): Map<String, Any?> {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        requireAuthenticated(channel)
        return tokenService.idClaims(channel.authContextId!!)
    }

    private fun requireAuthenticated(channel: ChannelSession) {
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState("Channel must be AUTHENTICATED for token/claims access")
        }
        checkNotNull(channel.authContextId) { "AUTHENTICATED channel without authContextId" }
    }

    private fun toActiveMethodViews(methods: List<AuthMethodView>?): List<ActiveMethodView> =
        methods.orEmpty().map { ActiveMethodView(requireNotNull(it.id) { "Active method without an id" }, it.method, it.label) }

    private fun resumeChannel(channel: ChannelSession): ChannelResponse {
        // LOGGED_OUT is terminal (docs/02-domaenenmodell.md #3) - without this, a GET on an old
        // channelSessionId would silently hand back a fresh login attempt on a dead channel.
        if (channel.state == ChannelState.LOGGED_OUT) return respond(channel)

        val channelId = channel.channelSessionId!!
        journeyService.findActive(channelId)?.let { return respond(channel, journeyService.nextOf(it, channel)) }
        if (channel.state == ChannelState.AUTHENTICATED) return respond(channel)

        return startEntryJourney(channel)
    }

    private fun startEntryJourney(channel: ChannelSession): ChannelResponse {
        val step = journeyService.startEntryJourney(channel)
        return respond(sessionManagementService.findChannelSessionById(channel.channelSessionId!!)!!, step.next, step.stepData)
    }

    fun raiseRequiredAcr(channelSessionId: UUID, bindingKeyRef: String, requiredAcr: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        sessionManagementService.raiseChannelAcrFloor(channelSessionId, requiredAcr)
        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!

        val floor = refreshed.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = refreshed.accountId?.let { accountService.findAccount(it) }
        if (authPolicy.isSatisfied(currentEvidence(refreshed), floor, account)) return respond(refreshed)

        val step = journeyService.startTowardAcr(
            refreshed,
            AuthIntent.STEP_UP,
            targetAcr = floor,
            startingAcr = authPolicy.resolveAcr(currentEvidence(refreshed), account)
        )
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /** Abandons the running journey and offers a fresh start where applicable. */
    fun cancelActiveJourney(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active journey to cancel for this channel")

        journeyService.cancel(active, channel)

        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        return if (refreshed.state == ChannelState.AUTHENTICATED) respond(refreshed) else startEntryJourney(refreshed)
    }

    /**
     * Ends this channel for good (docs/02-domaenenmodell.md #3: AUTHENTICATED -> LOGGED_OUT ->
     * terminal): cancels any running journey and discards this session's AuthContext. Unlike
     * [cancelActiveJourney], a logged-out channel is never resurrected.
     */
    fun logout(channelSessionId: UUID, bindingKeyRef: String) {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        journeyService.findActive(channelSessionId)?.let { journeyService.cancel(it, channel) }

        val afterCancel = sessionManagementService.findChannelSessionById(channelSessionId)!!
        afterCancel.authContextId = null
        afterCancel.state = ChannelState.LOGGED_OUT
        sessionManagementService.updateChannelSession(afterCancel)
    }

    /**
     * Voluntarily add another method. The loa2 gate and the step-up that may precede it belong to
     * the MANAGE strategy, not here - which is why the wish survives that detour instead of being
     * replaced by it.
     */
    fun startManageMethods(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse =
        startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.AddRequested)

    /**
     * Deactivate an active method instance. Addressed by [methodInstanceId], never by method name
     * (docs/03-tool-architektur.md, allowsMultipleInstances): several active entries can share a
     * method name, so a name alone can't tell them apart. Deliberately not restricted to this
     * device's own instances - a lost or stolen device must be removable from any session.
     */
    fun deactivateMethod(channelSessionId: UUID, bindingKeyRef: String, methodInstanceId: String): ChannelResponse =
        startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.RemoveRequested(methodInstanceId))

    private fun startManage(channelSessionId: UUID, bindingKeyRef: String, wish: ManageAuthMethodsState): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState("Channel must be AUTHENTICATED to manage methods")
        }
        checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }

        val step = journeyService.start(channel, AuthIntent.MANAGE_AUTH_METHODS, seed = wish)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /**
     * Starts the account-deletion journey: an unconditional yes/no confirmation, then a fresh
     * re-proof of any active factor, before the account is actually deleted (docs/05-api.md,
     * Account löschen). Resource-oriented like [startManageMethods]'s `enrollments`/step-ups.
     */
    fun startDeleteAccount(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState("Channel must be AUTHENTICATED to delete the account")
        }
        checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }

        val step = journeyService.start(channel, AuthIntent.DELETE_ACCOUNT)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /** The user's answer to whatever the current step is waiting on instead of a tool run. */
    fun answer(channelSessionId: UUID, bindingKeyRef: String, answer: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active journey for this channel")
        val step = journeyService.answer(active, channel, answer)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    private fun currentEvidence(channel: ChannelSession): AuthEvidence {
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        return AuthEvidence(authContext?.currentAmr ?: emptyList(), authContext?.currentFactorTypes ?: emptySet())
    }

    private fun respond(channel: ChannelSession, next: Next? = null, stepData: Map<String, Any?>? = null): ChannelResponse {
        // A terminal channel (docs/02-domaenenmodell.md #3) never has a next - regardless of what
        // a caller passed in, so no caller (e.g. a strategy's own now-meaningless placeholder
        // after account deletion) can accidentally resurrect a dead channel with a stray next.
        val resolved = if (channel.state?.isTerminal == true) {
            null
        } else {
            next ?: journeyService.findActive(channel.channelSessionId!!)?.let { journeyService.nextOf(it, channel) }
                ?: if (channel.state == ChannelState.AUTHENTICATED) Next.AUTHENTICATED else null
        }
        return ChannelResponse(
            channel = buildChannelBlock(channel, includeAccountFields = true),
            next = resolved,
            stepData = stepData
        )
    }

    /**
     * The channel-level block shared by every response, channel- and tool-level alike
     * (docs/05-api.md #2) - public so the tool controllers can attach it without a separate
     * `GET /channels` round-trip.
     *
     * [includeAccountFields] gates `currentAcr`/`currentAmr`/`activeMethods`, default `false`:
     * tool controllers are the common caller and never need them - the security-summary screen
     * that reads these is fetched on demand, so it never belongs in the core flow contract. Even
     * when `true`, they only actually appear once [ChannelSession.hasProvenFactor] - a
     * recognized-but-unproven device (`accountId` set via `DeviceAccountLink`, no evidence yet)
     * must not leak the account's active methods before anything was proven on THIS channel.
     */
    fun buildChannelBlock(channel: ChannelSession, includeAccountFields: Boolean = false): ChannelBlock {
        if (!includeAccountFields || !channel.hasProvenFactor) {
            return ChannelBlock(channelSessionId = channel.channelSessionId!!, state = channel.state?.name ?: ChannelState.ANONYMOUS.name)
        }
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        return ChannelBlock(
            channelSessionId = channel.channelSessionId!!,
            state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
            currentAcr = authContext?.currentAcr,
            currentAmr = authContext?.currentAmr,
            activeMethods = toActiveMethodViews(channel.accountId?.let { accountService.findAccount(it)?.activeAuthenticationMethods })
        )
    }

    companion object {
        // The device's long-lived identity lives in DeviceAccountLink, not the ChannelSession -
        // this only needs to outlive a single app session, not 30 days.
        private val CHANNEL_TTL: Duration = Duration.ofHours(24)
    }
}
