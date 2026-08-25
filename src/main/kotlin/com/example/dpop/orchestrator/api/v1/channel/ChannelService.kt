package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.orchestration.CandidateOffering
import com.example.dpop.orchestrator.orchestration.Next
import com.example.dpop.orchestrator.orchestration.ProcessCancellationService
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolCategory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional
class ChannelService(
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val authContextService: AuthContextService,
    private val authPolicy: AuthPolicy,
    private val toolRegistry: ToolHandlerRegistry,
    private val channelAccessGuard: ChannelAccessGuard,
    private val processCancellationService: ProcessCancellationService
) {

    /**
     * Always mints a brand-new ChannelSession - DPoP proves which device this is, but is
     * deliberately never a lookup key for resuming a session (docs/02-domaenenmodell.md #3).
     * The client must remember `channelSessionId` and call [getChannel] to resume; calling this
     * again (e.g. because it forgot the id, or after an explicit [logout]) legitimately starts a
     * new one. [DeviceAccountLink] is the one thing that DOES survive on the same key: a device
     * that was already registered still skips straight to LOGIN instead of a fresh `ident-fsc`.
     *
     * [intent] (docs/04-orchestrierung.md, lookup-based login) lets the client override that
     * default just for THIS channel, without affecting the durable [DeviceAccountLink]:
     * - `null`/`"auto"` (default): unchanged behaviour above.
     * - `"login"`: always offers lookup-based login (email + credential), even on an already
     *   linked device - e.g. to use a different account on the same device. The link lookup is
     *   suppressed entirely for this channel; a successful lookup login re-links the device to
     *   whichever account it just proved (idempotent, [linkDeviceToAccount]).
     * - `"register"`: always starts fresh REGISTRATION, even on an already linked device (a
     *   second account on the same device) - likewise suppresses the link lookup.
     */
    fun initializeChannel(bindingKeyRef: String, requestedRequiredAcr: String?, intent: String? = null): ChannelResponse {
        val linkedAccountId = if (intent == null || intent == "auto") {
            sessionManagementService.findLinkedAccountId(bindingKeyRef)
        } else {
            null
        }
        val channel = sessionManagementService
            .createChannelSession(bindingKeyRef, ChannelSession.Channel.APP, CHANNEL_TTL, linkedAccountId)
        requestedRequiredAcr?.let { sessionManagementService.raiseChannelRequiredAcr(channel.channelSessionId!!, it) }
        val refreshed = sessionManagementService.findChannelSessionById(channel.channelSessionId!!)!!
        return if (intent == "login") startLookupLogin(refreshed) else resumeChannel(refreshed)
    }

    /** The guaranteed resume entry point (docs/05-api.md #2): re-derives the currently due `next`, not just a stored snapshot. */
    fun getChannel(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse =
        resumeChannel(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))

    /** Same data as ChannelResponse.activeMethods, addressable as its own resource (docs/05-api.md #2). */
    fun getMethods(channelSessionId: UUID, bindingKeyRef: String): MethodsResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val methods = channel.accountId?.let { accountService.findAccount(it)?.activeAuthenticationMethods }
        return MethodsResponse(toActiveMethodViews(methods))
    }

    private fun toActiveMethodViews(methods: List<AuthMethodView>?): List<ActiveMethodView> =
        methods.orEmpty().map { ActiveMethodView(requireNotNull(it.id) { "Active method without an id" }, it.method, it.label) }

    private fun resumeChannel(channel: ChannelSession): ChannelResponse {
        // A logged-out channel stays logged out (docs/02-domaenenmodell.md #3: LOGGED_OUT is
        // terminal) - without this, GET on an old channelSessionId after logout would silently
        // re-derive and hand back a fresh login attempt on a channel that's supposed to be dead.
        if (channel.state == ChannelState.LOGGED_OUT) {
            return buildResponseForChannel(channel)
        }
        val channelId = channel.channelSessionId!!
        if (sessionManagementService.findActiveProcessSession(channelId) != null) {
            return buildResponseForChannel(channel)
        }
        return if (channel.accountId == null) startRegistration(channel) else resumeOrStartLogin(channel)
    }

    fun raiseRequiredAcr(channelSessionId: UUID, bindingKeyRef: String, requiredAcr: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        sessionManagementService.raiseChannelRequiredAcr(channelSessionId, requiredAcr)
        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!

        val floor = refreshed.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = refreshed.accountId?.let { accountService.findAccount(it) }
        return if (authPolicy.isSatisfied(currentEvidence(refreshed), floor, account)) {
            buildResponseForChannel(refreshed)
        } else {
            startStepUp(refreshed, floor)
        }
    }

    /** Abandons whatever REGISTRATION/LOGIN/STEP_UP is currently active and offers a fresh start where applicable. */
    fun cancelActiveProcess(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = sessionManagementService.findActiveProcessSession(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active process to cancel for this channel")

        processCancellationService.cancel(active, channel)

        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        return when {
            refreshed.state == ChannelState.AUTHENTICATED -> buildResponseForChannel(refreshed)
            refreshed.accountId == null -> startRegistration(refreshed)
            else -> resumeOrStartLogin(refreshed)
        }
    }

    /**
     * Ends this channel for good (docs/02-domaenenmodell.md #3: AUTHENTICATED -> LOGGED_OUT ->
     * terminal): cancels any in-flight process and discards this session's AuthContext. Never
     * resurrected afterwards - unlike [cancelActiveProcess], which offers a fresh start on the
     * SAME channel, a logged-out channel stays logged out; the client must call
     * [initializeChannel] again for a new ChannelSession ([DeviceAccountLink] still gets a known
     * device straight to LOGIN there).
     */
    fun logout(channelSessionId: UUID, bindingKeyRef: String) {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)

        sessionManagementService.findActiveProcessSession(channelSessionId)?.let {
            processCancellationService.cancel(it, channel)
        }

        val afterCancel = sessionManagementService.findChannelSessionById(channelSessionId)!!
        afterCancel.authContextId = null
        afterCancel.state = ChannelState.LOGGED_OUT
        sessionManagementService.updateChannelSession(afterCancel)
    }

    /**
     * Voluntary enrollment on an already-AUTHENTICATED channel (docs/04-orchestrierung.md,
     * ProcessPurpose.MANAGE_METHODS): reuses AuthPolicy.enrollmentCandidates and the existing
     * enroll-* tools unchanged, but finishing does NOT depend on canAccountReach/isSatisfied -
     * ToolOutcomeProcessor.resolveNext ends the process after exactly one Enrolled outcome. To
     * add another method, call this again.
     */
    fun startManageMethods(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState("Channel must be AUTHENTICATED to manage methods")
        }
        val accountId = checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelSessionId")

        requireManageMethodsAssurance(channel, account)?.let { return it }

        val floor = channel.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val candidates = authPolicy.enrollmentCandidates(account, floor)
        if (candidates.isEmpty()) {
            // Nothing left to add - not an error, just nothing to do; stays AUTHENTICATED with
            // no active process (docs/07-betrieb.md #1: HTTP errors are for disrupted flows, not
            // an expectable "you already have everything" outcome).
            return buildResponseForChannel(channel, mapOf("message" to "Keine weiteren Mittel verfuegbar"))
        }

        val processSession = sessionManagementService.createManageMethodsProcessSession(channelSessionId, PROCESS_TTL)
        processSession.accountId = accountId
        val offer = CandidateOffering.resolve(candidates, "enrollment")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelSessionId)!!, offer.stepData)
    }

    /**
     * Deactivates an active method instance on an already-AUTHENTICATED channel. Guarded against
     * self-lockout: rejected if the account could no longer reach its own channel's required
     * floor afterwards. Same loa2-in-this-session requirement as [startManageMethods] - removing
     * a factor is at least as sensitive as adding one.
     *
     * Addressed by [methodInstanceId], never by method name (docs/03-tool-architektur.md,
     * allowsMultipleInstances): several active entries can share a method name (e.g. two
     * devices), so a name alone can't tell them apart. Deliberately not filtered to "only devices
     * belonging to THIS physical device" either - a lost/stolen device's credential must be
     * removable from any authenticated session, not only from that device itself.
     */
    fun deactivateMethod(channelSessionId: UUID, bindingKeyRef: String, methodInstanceId: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState("Channel must be AUTHENTICATED to manage methods")
        }
        val accountId = checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelSessionId")

        requireManageMethodsAssurance(channel, account)?.let { return it }

        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound("No active method '$methodInstanceId' for this account")

        val afterRemoval = account.copy(
            authenticationMethods = account.authenticationMethods.map {
                if (it.id == methodInstanceId) it.copy(active = false) else it
            }
        )
        val floor = channel.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        if (!authPolicy.canAccountReach(afterRemoval, floor)) {
            throw OrchestratorException.invalidState(
                "Deaktivieren von '${target.method}' wuerde das Mindestniveau dieses Kanals unterschreiten"
            )
        }

        accountService.deactivateAuthenticationMethod(accountId, methodInstanceId)
        return buildResponseForChannel(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))
    }

    /**
     * Gate before add/deactivate: MANAGE_METHODS requires the CURRENT session to already carry
     * loa2 (via combined factors or ident-fsc), mirroring enrolledUnderAcr's anti-self-escalation
     * reasoning - a hijacked loa1 session must not be able to add or remove credentials on its
     * own say-so. Returns a step-up ChannelResponse if not yet satisfied, null if the caller may
     * proceed.
     */
    private fun requireManageMethodsAssurance(channel: ChannelSession, account: AccountProfile): ChannelResponse? {
        val evidence = currentEvidence(channel)
        if (authPolicy.isSatisfied(evidence, MANAGE_METHODS_REQUIRED_ACR, account)) return null
        return startStepUp(channel, MANAGE_METHODS_REQUIRED_ACR, allowReIdent = true)
    }

    private fun startRegistration(channel: ChannelSession): ChannelResponse {
        val channelId = channel.channelSessionId!!
        sessionManagementService.updateChannelState(channelId, ChannelState.REGISTERING)
        val processSession = sessionManagementService.createRegistrationProcessSession(channelId, PROCESS_TTL)

        // Same skip-if-single-candidate rule as ENROLL/AUTH (docs/04-orchestrierung.md #1): with
        // exactly one ident method, there is nothing to choose between.
        val identOptions = toolRegistry.descriptors().filter { it.category == ToolCategory.IDENT }.map { it.toolId }
        val offer = CandidateOffering.resolve(identOptions, "registration", "selectIdentificationMethod")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun resumeOrStartLogin(channel: ChannelSession): ChannelResponse {
        val channelId = channel.channelSessionId!!
        val evidence = currentEvidence(channel)
        val floor = channel.requiredAcr ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = accountService.findAccount(channel.accountId!!)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelId")

        if (authPolicy.isSatisfied(evidence, floor, account)) {
            sessionManagementService.updateChannelState(channelId, ChannelState.AUTHENTICATED)
            return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!)
        }

        // Accurately reflect "not currently authenticated" while the login is in progress -
        // docs/02-domaenenmodell.md #3 models "start login" as leaving from ANONYMOUS. Without
        // this, a cancelled/abandoned login would leave the channel stuck reporting a stale
        // AUTHENTICATED from a previous session.
        sessionManagementService.updateChannelState(channelId, ChannelState.ANONYMOUS)
        val processSession = sessionManagementService.createLoginProcessSession(channelId, PROCESS_TTL)
        val offer = CandidateOffering.resolve(authPolicy.candidateTools(evidence, floor, account, channel.bindingKeyRef!!), "auth")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    /**
     * [allowReIdent] opts into also offering re-identification (ident-fsc) as a way to close the
     * gap - needed for [requireManageMethodsAssurance], where an account with only ONE enrolled
     * AUTH method would otherwise have no possible path to loa2 at all (nothing left to combine
     * it with). Deliberately NOT the default: ordinary step-ups (e.g. [raiseRequiredAcr]) keep
     * offering only already-enrolled AUTH methods, matching existing candidate-selection tests.
     */
    /**
     * intent="login" entry point (docs/04-orchestrierung.md, lookup-based login): unlike
     * [resumeOrStartLogin], the account is NOT known yet - `channel.accountId` is null here even
     * if the device happens to be linked, because [initializeChannel] deliberately suppressed
     * the link lookup for this intent. Offers the fixed `-lookup` tool set directly rather than
     * AuthPolicy.candidateTools, which needs an already-resolved account it cannot have yet.
     */
    private fun startLookupLogin(channel: ChannelSession): ChannelResponse {
        val channelId = channel.channelSessionId!!
        val processSession = sessionManagementService.createLoginProcessSession(channelId, PROCESS_TTL)
        val offer = CandidateOffering.resolve(LOOKUP_LOGIN_TOOL_IDS, "auth")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun startStepUp(channel: ChannelSession, requiredAcr: String, allowReIdent: Boolean = false): ChannelResponse {
        val channelId = channel.channelSessionId!!
        sessionManagementService.updateChannelState(channelId, ChannelState.STEP_UP_IN_PROGRESS)

        val account = accountService.findAccount(channel.accountId!!)
            ?: throw OrchestratorException.processGone("Account not found for channel $channelId")
        val evidence = currentEvidence(channel)
        val processSession = sessionManagementService.createStepUpProcessSession(channelId, requiredAcr, PROCESS_TTL)
        processSession.startingAcr = authPolicy.resolveAcr(evidence, account)
        val candidates = authPolicy.candidateTools(evidence, requiredAcr, account, channel.bindingKeyRef!!) +
            if (allowReIdent) authPolicy.reIdentCandidates(evidence, requiredAcr) else emptyList()
        val offer = CandidateOffering.resolve(candidates, "auth")
        applyNext(processSession, offer.next)
        sessionManagementService.updateProcessSession(processSession)

        return buildResponseForChannel(sessionManagementService.findChannelSessionById(channelId)!!, offer.stepData)
    }

    private fun applyNext(processSession: ProcessSession, next: Next) {
        if (next.type == "tool") processSession.setNextTool(next.toolId!!, next.step) else processSession.setNextFlow(next.context!!, next.step)
    }

    private fun currentEvidence(channel: ChannelSession): AuthEvidence {
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        return AuthEvidence(authContext?.currentAmr ?: emptyList(), authContext?.currentFactorTypes ?: emptySet())
    }

    private fun buildResponseForChannel(channel: ChannelSession, stepData: Map<String, Any?>? = null): ChannelResponse {
        val active = sessionManagementService.findActiveProcessSession(channel.channelSessionId!!)
        val next = when {
            active?.nextType == "tool" -> Next.tool(active.nextToolId!!, active.nextStep!!, active.nextToolSessionId)
            active?.nextType == "flow" -> Next.flow(active.nextContext!!, active.nextStep!!)
            channel.state == ChannelState.AUTHENTICATED -> Next.flow("authentication", "authenticated")
            else -> null
        }
        return ChannelResponse(channel = buildChannelBlock(channel, includeAccountFields = true), next = next, stepData = stepData)
    }

    /**
     * The channel-level block shared by every response, channel- and tool-level alike
     * (docs/05-api.md #2) - public so [com.example.dpop.orchestrator.api.v1.tool.ToolControllerSupport]
     * and [com.example.dpop.orchestrator.api.v1.tool.ToolSwitchController] can attach it to tool
     * responses without a separate `GET /channels` round-trip.
     *
     * [includeAccountFields] gates `currentAcr`/`currentAmr`/`activeMethods`, default `false`:
     * tool controllers (10 today, and growing) are the common caller and never need them - the
     * security-summary screen that reads these fields is fetched on demand, not something a tool
     * flow lands on automatically, so it never belongs in the core flow contract. Only the real
     * channel resource (`GET`/`POST /channels`, `step-ups`, `enrollments`,
     * `DELETE .../methods/{methodInstanceId}`) opts in explicitly with `true` - the few endpoints whose
     * actual purpose is reporting or mutating that data.
     */
    fun buildChannelBlock(channel: ChannelSession, includeAccountFields: Boolean = false): ChannelBlock {
        if (!includeAccountFields) {
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
        // The device's long-lived identity now lives in DeviceAccountLink, not the
        // ChannelSession itself - this only needs to outlive a single app session/day, not 30.
        private val CHANNEL_TTL: Duration = Duration.ofHours(24)
        private val PROCESS_TTL: Duration = Duration.ofMinutes(60)
        private const val MANAGE_METHODS_REQUIRED_ACR = "loa2"
        private val LOOKUP_LOGIN_TOOL_IDS = listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")
    }
}
