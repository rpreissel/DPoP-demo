package com.example.dpop.orchestrator.channel

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.account.AccountService
import com.example.dpop.account.AuthMethodView
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.journey.Action
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.journeylog.JourneyLogResponse
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.journey.state.ConfirmPeerLoginState
import com.example.dpop.orchestrator.journey.state.ManageAuthMethodsState
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.kernel.AcrLevels
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.orchestrator.kernel.AmrSource
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelCreationThrottleService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.TokenProvider
import com.example.dpop.orchestrator.session.TokenService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.tool_api.ActiveMethodView
import com.example.dpop.tool_api.AuthData
import com.example.dpop.tool_api.ChannelBlock
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_api.isLocalAnchor
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DemoInfo
import com.example.dpop.tool_api.DemoSession
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.AcrLevel
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.dpop.orchestrator.session.forLog
import com.example.dpop.tool_spi.StepData

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
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val channelAccessGuard: ChannelAccessGuard,
    private val journeyService: JourneyService,
    private val tokenService: TokenService,
    private val tokenProvider: TokenProvider,
    private val channelCreationThrottleService: ChannelCreationThrottleService,
    private val journeyLogService: JourneyLogService,
    private val personDirectory: PersonDirectory,
    private val toolRegistry: ToolHandlerRegistry,
    private val demoDisclosure: DemoDisclosure
) {

    /**
     * Always mints a brand-new ChannelSession - DPoP proves which device this is, but is
     * deliberately never a lookup key for resuming a session (docs/02-domaenenmodell.md #3). The
     * client must remember `channelSessionId` and call [getChannel] to resume.
     *
     * [intent] picks the strategy for this channel and is REMEMBERED on it: resume and cancel
     * restart the same one. Only FAST and CONFIRM_PEER_LOGIN consult the durable
     * [DeviceAccountLink] - REGISTER and LOGIN_LOOKUP both mean "not the account this device
     * already knows".
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
            ?: throw OrchestratorException.invalidState(Text("Unbekannter Vorgang"), "intent=${intent}")
        if (!entryIntent.isEntryIntent) {
            throw OrchestratorException.invalidState(Text("Dieser Vorgang kann keinen Kanal eroeffnen"), "entryIntent=${entryIntent}")
        }

        // CONFIRM_PEER_LOGIN's cold-entry path depends on this exactly like FAST_ACCESS: no
        // DeviceAccountLink means no known account, which its own strategy treats as an immediate
        // abort rather than falling into identification/registration (see AuthIntent's own doc).
        val linkedAccountId = if (entryIntent == AuthIntent.FAST_ACCESS || entryIntent == AuthIntent.CONFIRM_PEER_LOGIN) {
            sessionManagementService.findLinkedAccountId(bindingKeyRef)
        } else {
            null
        }
        val channel = sessionManagementService
            .createChannelSession(bindingKeyRef, ChannelType.APP, CHANNEL_TTL, linkedAccountId)
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
     * Whether this device is already linked to an account (docs/02-domaenenmodell.md #1) - a pure
     * read against [SessionManagementService.findLinkedAccountId], no channel/journey created.
     * Lets the entry screen show "this device belongs to X" before the user picks how to start,
     * the same `bindingKeyRef` proof every other App-facade call already requires.
     */
    fun findDeviceLink(bindingKeyRef: String): DeviceLinkResponse {
        val accountId = sessionManagementService.findLinkedAccountId(bindingKeyRef) ?: return DeviceLinkResponse(linked = false)
        val personId = accountService.findAccount(accountId)?.personId
        val personName = personId?.let { personDirectory.displayName(it) }
        return DeviceLinkResponse(
            linked = true,
            accountId = accountId,
            personName = personName,
            boundCredentials = boundCredentials(accountId, bindingKeyRef)
        )
    }

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
     * The AccessToken - Mock (default profile) or a real, Keycloak-signed one (`keycloak`
     * profile) depending on [tokenProvider]. `APP`-only: a `KEYCLOAK` channel never
     * has an [ChannelSession.authContextId] to mint one from - its client already holds real
     * Keycloak tokens from the standard browser login and refreshes directly against Keycloak,
     * never through the orchestrator. Covers both first issuance and refresh -
     * [minValiditySeconds] is the caller's tolerance, the backend alone decides whether the
     * existing token still qualifies or a new one gets minted.
     */
    fun getToken(channelSessionId: UUID, bindingKeyRef: String, minValiditySeconds: Long): TokenResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        requireAuthenticatedApp(channel)
        val pair = tokenProvider.tokenFor(channel, minValiditySeconds)
        return TokenResponse(accessToken = pair.accessToken, accessExpiresAt = pair.accessExpiresAt, refreshExpiresAt = pair.refreshExpiresAt)
    }

    /** The fachliche (business) ID-token claims - a separate resource from the AccessToken's own claims. */
    fun getIdClaims(channelSessionId: UUID, bindingKeyRef: String): Map<String, Any?> {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        requireAuthenticatedApp(channel)
        return tokenService.idClaims(channel.authContextId!!)
    }

    /**
     * Token and ID claims exist only for an authenticated `APP` channel. The channel type is checked
     * first: a `KEYCLOAK` channel never has an [ChannelSession.authContextId], so it must be refused
     * as the wrong kind of channel (409), not trip over the missing context (500).
     */
    private fun requireAuthenticatedApp(channel: ChannelSession) {
        if (channel.channel != ChannelType.APP) {
            throw OrchestratorException.invalidState(Text("Token retrieval is only supported for APP channels"))
        }
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED for token/claims access"))
        }
        checkNotNull(channel.authContextId) { "AUTHENTICATED channel without authContextId" }
    }

    /**
     * `maxAcr`/`factorTypes` come from the tool catalog (a method's own, account-independent
     * ceiling); `enrolledUnderAcr`/`effectiveAcr` from the account's own enrollment record (the
     * ADR-5 cap, [DefaultAuthPolicy.canAccountReach]'s same `AcrLevel.min` calculation) - surfaced
     * here so the UI can show WHY a method might not reach as far as its own catalog entry
     * promises, instead of that only being discoverable later as a confusing rejection.
     */
    private fun toActiveMethodViews(methods: List<AuthMethodView>?): List<ActiveMethodView> =
        methods.orEmpty().map { m ->
            val descriptor = toolRegistry.descriptors().firstOrNull { it.method == m.method }
            ActiveMethodView(
                id = checkNotNull(m.id) { "Active method without an id" },
                method = m.method,
                label = m.label,
                factorTypes = descriptor?.factorTypes?.toList(),
                        maxAcr = descriptor?.maxAcr?.value,
                enrolledUnderAcr = m.enrolledUnderAcr,
                effectiveAcr = descriptor?.let { AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), it.maxAcr) }?.value
            )
        }

    /**
     * `internal`, not `private`: [KcChannelService] reuses this same "resume and advance the
     * active journey" logic for the kc-facade's upsert endpoint (docs/05-api.md
     * Abschnitt 3) instead of duplicating it - only channel creation differs per facade.
     */
    /**
     * [seedAction] only ever matters for the fresh-journey branch below (a channel with an
     * already-active journey never re-fires it; an already-AUTHENTICATED one never starts a new
     * one either) - `null` by default, see [JourneyService.start]'s own doc for when a caller
     * (`KcChannelService`'s RestoreData case) needs one.
     */
    internal fun resumeChannel(channel: ChannelSession, seedAction: Action? = null): ChannelResponse {
        // LOGGED_OUT is terminal (docs/02-domaenenmodell.md #3) - without this, a GET on an old
        // channelSessionId would silently hand back a fresh login attempt on a dead channel.
        if (channel.state == ChannelState.LOGGED_OUT) return respond(channel)

        val channelId = channel.channelSessionId!!
        journeyService.findActive(channelId)?.let {
            val step = journeyService.stepOf(it, channel)
            return respond(channel, step.next, step.stepData)
        }
        if (channel.state == ChannelState.AUTHENTICATED) return respond(channel)

        return startEntryJourney(channel, seedAction)
    }

    private fun startEntryJourney(channel: ChannelSession, seedAction: Action? = null): ChannelResponse {
        val step = journeyService.startEntryJourney(channel, seedAction)
        return respond(sessionManagementService.findChannelSessionById(channel.channelSessionId!!)!!, step.next, step.stepData)
    }

    fun raiseRequiredAcr(channelSessionId: UUID, bindingKeyRef: String, requiredAcr: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        sessionManagementService.raiseChannelAcrFloor(channelSessionId, requiredAcr)
        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!

        val floor = refreshed.acrFloor?.let(AcrLevel::of) ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = refreshed.accountId?.let { accountService.findAccount(it) }
        if (authPolicy.isSatisfied(currentEvidence(refreshed), floor, account)) return respond(refreshed)

        val step = journeyService.startTowardAcr(
            refreshed,
            targetAcr = floor,
            startingAcr = authPolicy.resolveAcr(currentEvidence(refreshed), account)
        )
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /** Abandons the running journey and offers a fresh start where applicable. */
    fun cancelActiveJourney(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey to cancel for this channel"))

        journeyService.cancel(active, channel)

        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        return if (refreshed.state == ChannelState.AUTHENTICATED) respond(refreshed) else startEntryJourney(refreshed)
    }

    /**
     * Ends this channel for good (docs/02-domaenenmodell.md #3: AUTHENTICATED -> LOGGED_OUT ->
     * terminal): cancels any running journey and discards this session's AuthContext. Unlike
     * [cancelActiveJourney], a logged-out channel is never resurrected.
     */
    /**
     * Starts a LOGOUT journey with a confirmation prompt. Only on AUTHENTICATED channels.
     * The actual logout happens when the user confirms via POST .../answer.
     */
    fun startLogout(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to start a logout journey"))
        }
        val activeJourney = journeyService.findActive(channelSessionId)
        if (activeJourney != null) {
            journeyService.cancel(activeJourney, channel)
        }
        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        val step = journeyService.start(refreshed, AuthIntent.LOGOUT)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /**
     * Direct logout without confirmation — cancels any running journey and ends the channel
     * immediately. For non-interactive clients or as a hard logout.
     */
    fun logout(channelSessionId: UUID, bindingKeyRef: String) {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val activeJourney = journeyService.findActive(channelSessionId)
        if (activeJourney != null) {
            journeyService.cancel(activeJourney, channel)
        } else {
            journeyLogService.recordForChannel(channel.forLog(), "LOGGED_OUT")
        }

        val refreshed = sessionManagementService.findChannelSessionById(channelSessionId)!!
        refreshed.authContextId = null
        refreshed.state = ChannelState.LOGGED_OUT
        sessionManagementService.updateChannelSession(refreshed)
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

    /**
     * Withdraws an account-owned attribute, through the same journey and the same gate as
     * removing a method - it is the same kind of destructive self-service act, and it can take
     * credentials with it (`JourneyActionExecutor.performRetractAttribute`).
     *
     * Only attributes an account actually owns locally may be withdrawn: a master-data field is
     * not ours to retract, and a method-owned one goes with its method. The wire name is resolved
     * here rather than passed through as a string, so an unknown one is refused right away (409,
     * like every other invalid request on a channel) instead of a silent no-op deep inside the journey.
     */
    fun retractAttribute(channelSessionId: UUID, bindingKeyRef: String, attribute: String): ChannelResponse {
        val attributeType = AttributeType.fromWireName(attribute)
            ?: throw OrchestratorException.invalidState(Text("Unbekanntes Attribut: {attribute}", "attribute" to attribute))
        if (!attributeType.isLocalAnchor) {
            throw OrchestratorException.invalidState(
                Text("'{attribute}' gehoert nicht dem Konto ({authority}) und kann hier nicht zurueckgenommen werden", "attribute" to attribute, "authority" to attributeType.authority)
            )
        }
        return startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.RetractAttributeRequested(attributeType))
    }

    private fun startManage(channelSessionId: UUID, bindingKeyRef: String, wish: ManageAuthMethodsState): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to manage methods"))
        }
        checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }

        val step = journeyService.start(channel, AuthIntent.MANAGE_AUTH_METHODS, seed = wish)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /**
     * Confirm a WEB-channel QR login from this already-authenticated APP channel
     * (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN) - the "already open app" entry into
     * [AuthIntent.CONFIRM_PEER_LOGIN], resource-oriented like [startManageMethods]. The cold-entry
     * path is a plain `POST /app/channels` with that same intent instead; both converge on
     * [ConfirmPeerLoginState.Requested].
     */
    fun startPeerLogin(channelSessionId: UUID, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to confirm a peer login"))
        }
        checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }

        val step = journeyService.start(
            channel, AuthIntent.CONFIRM_PEER_LOGIN,
            seed = ConfirmPeerLoginState.Requested(startedAuthenticated = true)
        )
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
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to delete the account"))
        }
        checkNotNull(channel.accountId) { "AUTHENTICATED channel without accountId" }

        val step = journeyService.start(channel, AuthIntent.DELETE_ACCOUNT)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    /** The user's answer to whatever the current step is waiting on instead of a tool run. */
    fun answer(channelSessionId: UUID, bindingKeyRef: String, answer: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey for this channel"))
        val step = journeyService.answer(active, channel, answer)
        return respond(sessionManagementService.findChannelSessionById(channelSessionId)!!, step.next, step.stepData)
    }

    private fun currentEvidence(channel: ChannelSession): AuthEvidence =
        channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.toCoreEvidence() ?: AuthEvidence(emptyList())

    private fun respond(channel: ChannelSession, next: Next? = null, stepData: StepData? = null): ChannelResponse {
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
            stepData = stepData,
            demo = demoInfo(channel),
            authData = authDataFor(channel)
        )
    }

    /**
     * `KEYCLOAK`-only (docs/05-api.md Abschnitt 3) - `null` for `APP`. Used both by
     * [respond] here and by `ToolControllerSupport`'s own response building, so every KEYCLOAK
     * response carries it, entry point and tool activation/PATCH alike, not just this class's own.
     */
    fun authDataFor(channel: ChannelSession): AuthData? {
        if (channel.channel != ChannelType.KEYCLOAK) return null
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val amr = evidence?.currentAmr?.associateWith { evidence.currentAmrSource[it] ?: AmrSource.ORCHESTRATOR }
        val acr = evidence?.let {
            val account = channel.accountId?.let { id -> accountService.findAccount(id) }
            authPolicy.resolveAcr(it.toCoreEvidence(), account)
        }
        return AuthData(accountId = channel.accountId, acr = acr?.value, amr = amr)
    }

    /**
     * Same demo-only journey-chain view as ToolControllerSupport's, for channel-level responses
     * (tool_api/Envelope.kt, JourneyDebugStep). Assembled by [DemoDisclosure], never here - that
     * bean is the one place that decides whether this deployment discloses demo values at all.
     */
    private fun demoInfo(channel: ChannelSession): DemoInfo? {
        val journeys = journeyService.debugChain(channel)
        val accountId = channel.accountId
        val personId = accountId?.let { accountService.findAccount(it)?.personId }
        return demoDisclosure.assemble(accountId, personId, journeys, session = demoSession(channel))
    }

    /**
     * Who the session belongs to and what it has proven so far - for the demo column, which shows
     * it at every step. Null until something was proven: before that there is nothing to show but
     * "not signed in", which the client says on its own. Same acr resolution as [buildChannelBlock].
     */
    fun demoSession(channel: ChannelSession): DemoSession? {
        if (!channel.hasProvenFactor) return null
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return DemoSession(
            authenticated = channel.state == ChannelState.AUTHENTICATED,
            personName = account?.personId?.let { personDirectory.displayName(it) },
            acr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }?.value,
            amr = evidence?.currentAmr ?: emptyList()
        )
    }

    /**
     * What else this device is known by, besides the DPoP channel key the client already shows:
     * for every key-bound credential of [accountId] that lives on [bindingKeyRef], the reference
     * its own method chooses to disclose ([ToolDescriptor.instanceDisclosure]) - the `device`
     * method's credential key, the identifier KOBIL gave this phone.
     *
     * Generic all the way through: which instance lives on this key is answered by the method's
     * own [ToolDescriptor.keyBinding], and what may be shown about it by its own disclosure. No
     * concrete method name or detail-map key appears here - this file used to name four of them,
     * which only compiled because `internal const val` is inlined and therefore left no module
     * edge behind.
     *
     * Resolved by `(method, IDENTIFIED_AUTH)`, the pair that names one concrete procedure - the
     * same rule `JourneyActionExecutor.credentialsLivingOn` uses, and for the same reason: by
     * method name alone an enrollment tool would answer just as readily, from the wrong
     * declaration.
     */
    private fun boundCredentials(accountId: Long, bindingKeyRef: String): List<BoundCredentialView> =
        accountService.findAccount(accountId)?.activeAuthenticationMethods.orEmpty().mapNotNull { instance ->
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == instance.method }
                ?: return@mapNotNull null
            if (descriptor.keyBinding?.livesOn(instance.details, bindingKeyRef) != true) return@mapNotNull null
            descriptor.instanceDisclosure?.referenceOf(instance.details)
                ?.let { BoundCredentialView(method = instance.method, reference = it) }
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
        val channelType = checkNotNull(channel.channel) { "Channel without a channel type" }.name
        if (!includeAccountFields || !channel.hasProvenFactor) {
            return ChannelBlock(
                channelSessionId = channel.channelSessionId!!,
                channelType = channelType,
                state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
                hasProvenFactor = channel.hasProvenFactor
            )
        }
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        val currentAcr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }
        return ChannelBlock(
            channelSessionId = channel.channelSessionId!!,
            channelType = channelType,
            state = channel.state?.name ?: ChannelState.ANONYMOUS.name,
            hasProvenFactor = channel.hasProvenFactor,
            currentAcr = currentAcr?.value,
            currentAmr = evidence?.currentAmr,
            activeMethods = toActiveMethodViews(account?.activeAuthenticationMethods)
        )
    }

    companion object {
        // The device's long-lived identity lives in DeviceAccountLink, not the ChannelSession -
        // this only needs to outlive a single app session, not 30 days.
        private val CHANNEL_TTL: Duration = Duration.ofHours(24)
    }
}
