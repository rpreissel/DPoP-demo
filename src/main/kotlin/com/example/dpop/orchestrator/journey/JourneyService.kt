package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.state.ToolRef
import com.example.dpop.tool_api.JourneyDebugStep
import com.example.dpop.tool_api.Next
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.Reachability
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AmrSource
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.AuthEvidenceService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.toCoreEvidence
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.kc.KeycloakAdminClient
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.IdentityResolver
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_api.Resolution
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.DEMO_DATA_KEY
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.assertClaimsCovered
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The machinery between the [IntentStrategy] SPI and the rest of the orchestrator: it turns a
 * [Transition] into the next state, derives `next` from that state, and executes everything a
 * strategy is deliberately not allowed to do itself.
 *
 * The split is the point. A strategy answers "what does this mean" and "where to next" as pure
 * values; account creation, evidence recording, device linking and the ACR cap happen here, once,
 * for every intent alike - so no intent can forget the cap or skip the audit trail.
 */
@Service
@Transactional
class JourneyService(
    private val journeyRepository: AuthJourneyRepository,
    private val codec: JourneyStateCodec,
    strategies: List<IntentStrategy<*>>,
    private val accountService: AccountService,
    private val identityResolver: IdentityResolver,
    private val authContextService: AuthContextService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val toolRegistry: ToolHandlerRegistry,
    private val authPolicy: AuthPolicy,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val featureFlagProviders: List<FeatureFlagProvider>,
    private val accountDeletionService: AccountDeletionService,
    private val journeyLogService: JourneyLogService,
    private val journeyLogDetails: JourneyLogDetails,
    private val journeyRecorder: JourneyRecorder,
    // Optional: only present under the `keycloak` profile (KeycloakAdminClient's own doc) -
    // JourneyService itself runs in every profile, so it must tolerate the bean being absent.
    private val keycloakAdminClient: ObjectProvider<KeycloakAdminClient>
) {
    /**
     * `next` plus whatever the step needs to render - the pair every caller wants back. `next` is
     * null only for a decision that ends the channel for good ([Transition.Logout]) -
     * ChannelService.respond() derives the real next itself in every other case.
     */
    data class Step(val next: Next?, val stepData: Map<String, Any?>? = null)

    private val strategiesByIntent: Map<AuthIntent, IntentStrategy<*>> = strategies.associateBy { it.intent }

    init {
        val missing = AuthIntent.entries.filterNot { it in strategiesByIntent }
        check(missing.isEmpty()) { "No IntentStrategy registered for: $missing" }
    }

    // Lifecycle ---------------------------------------------------------------

    /**
     * Starts a journey and immediately produces its first offer. [seed] lets a caller name the
     * concrete wish the journey exists for (a step-up target, a method to remove) - without it
     * the strategy's own [IntentStrategy.initialState] applies.
     *
     * [seedAction] is the journey's Anfangs-Übergang (docs/ideen/journey-strategie-vereinheit
     * lichung.md #3): a mechanical action that runs BEFORE `initialState()`, decided by no
     * strategy - today only `KcChannelService`'s RestoreData case
     * ([Action.ApplyRestoredEvidence]). It is still a real, logged transition (`"Entry"`), just
     * one no [IntentStrategy] ever sees: by the time [IntentStrategy.initialState] runs, its
     * effect (e.g. restored evidence) is already reflected in [contextFor]'s output, so a
     * strategy that already checks sufficiency on `Started` (like `KcSelectMethodStrategy`) needs
     * no special case for it at all.
     */
    fun start(
        channel: ChannelSession,
        intent: AuthIntent,
        seed: JourneyState? = null,
        parentJourneyId: UUID? = null,
        seedAction: Action? = null
    ): Step {
        val journey = AuthJourney(channel.channelSessionId, intent, Instant.now().plus(JOURNEY_TTL))
        journey.accountId = channel.accountId
        journey.parentJourneyId = parentJourneyId
        // A step-up says so on the channel, whether it was asked for directly or demanded as
        // another journey's precondition - the client renders the same screen either way.
        if (intent == AuthIntent.STEP_UP && channel.state != ChannelState.STEP_UP_IN_PROGRESS) {
            channel.state = ChannelState.STEP_UP_IN_PROGRESS
            sessionManagementService.updateChannelSession(channel)
        }
        val strategy = strategyFor(intent)
        // A placeholder write, purely to satisfy the state columns' NOT NULL constraint before the
        // row can be persisted at all - superseded below once seedAction (if any) has actually run,
        // so no strategy ever observes it.
        codec.write(journey, seed ?: strategy.initialState(contextFor(journey, channel)))
        journeyRepository.save(journey) // journeyId exists from here on, for seedAction's own logging/events.

        seedAction?.let { action ->
            // Anfangs-Übergang der Maschine (Statecharts: Pseudostate -> q0, mit Aktion) -
            // mechanisch, von keiner Strategie entschieden, aber ganz normal über dieselbe
            // Pipeline geloggt wie jeder andere Übergang.
            journeyLogService.record(channel, journey, "Entry", detail = journeyLogDetails.actionDetail(action, journey, channel))
            performAction(journey, channel, action)
            // Re-derive now that the seed's own effect (e.g. restored evidence) is reflected in ctx
            // - initialState() must never see the placeholder's stale, pre-seed picture.
            codec.write(journey, seed ?: strategy.initialState(contextFor(journey, channel)))
            journeyRepository.save(journey)
        }

        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "JOURNEY_STARTED:$intent", "orchestrator"
        )
        return advance(journey, channel, JourneyEvent.Started)
    }

    /**
     * Starts STEP_UP seeded toward [targetAcr], entered directly (the App channel's own step-up
     * trigger) instead of as another journey's precondition - the only caller of this ever wants
     * STEP_UP specifically, so it seeds via [StepUpState.forSubJourney] itself rather than through
     * some generic per-intent seeding mechanism.
     */
    fun startTowardAcr(channel: ChannelSession, targetAcr: AcrLevel, startingAcr: AcrLevel): Step =
        start(channel, AuthIntent.STEP_UP, seed = StepUpState.forSubJourney(targetAcr, startingAcr))

    /**
     * Starts (or restarts) whatever intent this channel was entered with. The intent lives on the
     * channel precisely so neither resume nor cancel has to guess from leftover state what the
     * user was trying to do. [seedAction] is passed straight through to [start] - see that
     * parameter's own doc.
     */
    fun startEntryJourney(channel: ChannelSession, seedAction: Action? = null): Step {
        if (channel.state != ChannelState.AUTHENTICATED) {
            channel.state = if (channel.accountId == null) ChannelState.REGISTERING else ChannelState.ANONYMOUS
            sessionManagementService.updateChannelSession(channel)
        }
        return start(channel, channel.entryIntent, seedAction = seedAction)
    }

    /** The one running journey of this channel, if any - a suspended parent is deliberately not it. */
    fun findActive(channelSessionId: UUID): AuthJourney? =
        journeyRepository
            .findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(channelSessionId, JourneyLifecycle.STARTED)
            ?.takeIf { !it.isExpired }

    fun findById(journeyId: UUID): AuthJourney? =
        journeyRepository.findByIdOrNull(journeyId)?.takeIf { !it.isExpired }

    /**
     * Debug-only view of the running journey chain (docs/tool_api/Envelope.kt, [JourneyDebugStep])
     * - the currently active journey plus every SUSPENDED ancestor it is a sub-journey of, walked
     * via [AuthJourney.parentJourneyId], outermost first. Empty once nothing is running.
     */
    fun debugChain(channel: ChannelSession): List<JourneyDebugStep> {
        val channelSessionId = channel.channelSessionId ?: return emptyList()
        val innermost = findActive(channelSessionId) ?: return emptyList()
        val chain = mutableListOf(innermost)
        var current = innermost
        while (true) {
            val parent = current.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) } ?: break
            chain.add(parent)
            current = parent
        }
        val availableTools = availableToolsOf(channel)
        // Only the innermost (actually active) journey's state has a current step worth
        // explaining - a SUSPENDED parent is parked waiting on its sub-journey, not offering
        // anything itself.
        val innermostNote = DemoStepReason.explain(codec.read(innermost), availableTools)
        return chain.reversed().map {
            JourneyDebugStep(
                journeyId = it.journeyId.toString(),
                intent = it.intent!!.name,
                lifecycle = it.lifecycle.name,
                stateType = it.stateType!!,
                note = if (it == innermost) innermostNote else null
            )
        }
    }

    fun stateOf(journey: AuthJourney): JourneyState = codec.read(journey)

    /** User-initiated abandonment of the whole journey, distinct from an exhausted budget. */
    fun cancel(journey: AuthJourney, channel: ChannelSession) {
        markCancelled(journey, channel)
        fallBack(journey, channel)
    }

    private fun markCancelled(journey: AuthJourney, channel: ChannelSession) {
        journey.cancel()
        journeyRepository.save(journey)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "JOURNEY_CANCELLED", "orchestrator"
        )
        journeyLogService.record(channel, journey, "CANCELLED", journeyState = codec.read(journey)::class.simpleName)
    }

    // Routing -----------------------------------------------------------------

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). The same
     * [JourneyState.activatable] that answers "may this tool be activated" also decides where the
     * client goes - one function, so the two can never disagree.
     */
    fun nextOf(journey: AuthJourney, channel: ChannelSession): Next = nextFor(codec.read(journey), availableToolsOf(channel))

    /** Returns the complete current step, including selection options and prompts. */
    fun stepOf(journey: AuthJourney, channel: ChannelSession): Step =
        stepFor(codec.read(journey), availableToolsOf(channel))

    private fun nextFor(state: JourneyState, availableTools: Set<ToolId>): Next {
        state.active?.let { return Next.tool(it.toolId.value, it.step, it.toolSessionId) }
        val activatable = state.activatable(availableTools)
        return if (activatable.size == 1) {
            val toolId = activatable.single()
            Next.tool(toolId.value, toolRegistry.descriptorOf(toolId).startStep)
        } else {
            // Several candidates open a selection page; zero means an orchestrator-owned page
            // that isn't a choice at all (a confirmation, the finished screen), or a state whose
            // only offer just became unavailable - the empty option list resolves itself once the
            // client's next action (abandon/activate) drives an actual transition.
            Next.orchestrator(state.selectionContext, state.selectionStep)
        }
    }

    private fun stepFor(state: JourneyState, availableTools: Set<ToolId>): Step {
        val options = state.activatable(availableTools)
        val stepData = buildMap<String, Any?> {
            if (state is OfferingState && options.size > 1) {
                put("options", options.map { it.value })
                put("title", state.selectionTitle)
                state.selectionDescription?.let { put("description", it) }
            }
            // Single-option auto-activate: the selection screen is skipped, so pass the
            // description as a contextual message so the tool form can explain WHY this
            // step is required (e.g. "E-Mail-Bestätigung ausstehend" during fast-access).
            if (state is OfferingState && options.size == 1) {
                state.selectionDescription?.let { put("message", it) }
            }
            if (state is AnswerableState) put("prompt", state.prompt)
        }
        return Step(nextFor(state, availableTools), stepData.ifEmpty { null })
    }

    /** Live, never cached: a backend disable must take effect on the very next step of an already-running journey. */
    private fun availableToolsOf(channel: ChannelSession): Set<ToolId> =
        (channel.availableClientTools - toolAvailabilityService.disabledToolIds()).mapTo(mutableSetOf()) { ToolId(it) }

    // Tool interaction ---------------------------------------------------------

    /**
     * Claims [toolSessionId] as THE current attempt for [tool]. Rejects anything the current
     * state does not offer - which is why LOGIN_LOOKUP cannot be talked into an identification:
     * no state of that intent ever lists one.
     */
    fun activate(journey: AuthJourney, channel: ChannelSession, tool: ToolDescriptor, toolSessionId: UUID) {
        val state = codec.read(journey)
        if (tool.toolId !in state.activatable(availableToolsOf(channel))) {
            throw OrchestratorException.invalidState("${tool.toolId} is not offered in the current step")
        }
        // A concurrent/duplicate activation mints its own ToolSession too; only the one that lands
        // here last becomes current, and the other is correctly rejected by isCurrent afterwards.
        codec.write(journey, state.withActive(ToolRef(tool.toolId, toolSessionId, tool.startStep)))
        journeyRepository.save(journey)
        journeyLogService.record(channel, journey, "TOOL_ACTIVATED", journeyState = state::class.simpleName, detail = mapOf("toolId" to tool.toolId))
    }

    fun isCurrent(journey: AuthJourney, toolId: ToolId, toolSessionId: UUID): Boolean =
        codec.read(journey).active?.let { it.toolId == toolId && it.toolSessionId == toolSessionId } ?: false

    fun applyOutcome(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome
    ): Step = when (outcome) {
        is ToolOutcome.InProgress -> {
            val state = codec.read(journey)
            val active = checkNotNull(state.active) { "InProgress without an active tool" }
            codec.write(journey, state.withActive(active.copy(step = outcome.nextStep)))
            journeyRepository.save(journey)
            Step(Next.tool(tool.toolId.value, outcome.nextStep, active.toolSessionId), outcome.data)
        }

        is ToolOutcome.Failed -> chargeAttempt(journey, channel, tool, outcome)

        // What this outcome MEANS - and executing that meaning - is entirely the strategy's/
        // machine's business from here: transition() turns it into a Transition.Perform naming
        // the Action, applyTransition executes it (recording the resulting MethodEvidence as part
        // of that, see performAction) and re-derives the journey with a fresh, post-action context.
        is ToolOutcome.Completed -> advance(journey, channel, JourneyEvent.Completed(tool, outcome))
    }

    /** "Back"/"Switch": the tool is abandoned, and the state decides whether anything is left. */
    fun abandon(journey: AuthJourney, channel: ChannelSession, tool: ToolDescriptor): Step {
        val state = codec.read(journey)
        codec.write(journey, state.withActive(null))
        journeyRepository.save(journey)
        return advance(journey, channel, JourneyEvent.Abandoned(tool))
    }

    /**
     * An answer to whatever the current state is waiting on ([AnswerableState]) instead of a tool
     * run. One generic entry point for every such action, present and future: which state
     * implements [AnswerableState], which [answer] values are valid, and what its strategy's
     * `transition` decides for them can all change without this method ever changing.
     */
    fun answer(journey: AuthJourney, channel: ChannelSession, answer: String): Step {
        val state = codec.read(journey)
        if (state !is AnswerableState) {
            throw OrchestratorException.invalidState("Nothing is currently waiting for an answer")
        }
        return advance(journey, channel, JourneyEvent.Answered(answer))
    }

    /**
     * A facade-neutral sync of [source]'s complete, currently-valid evidence set (docs/05-api.md
     * Abschnitt 3) for a channel that already has a running journey - never a real
     * orchestrator tool outcome, so [AuthEvidenceService.attachToChannel] is called directly
     * instead of going through [applyOutcome]. This method itself knows nothing about Keycloak -
     * the caller (`KcChannelService`) supplies [source] explicitly (`AmrSource.KEYCLOAK` today,
     * but nothing here hardcodes that) and its own real `amrSourceId` per method (`MethodEvidence.
     * amrSourceId` - Keycloak's own authenticator/execution id; never left blank, see that
     * field's own doc).
     *
     * A fresh channel's FIRST evidence, primed from a resubmitted RestoreData token rather than
     * proved just now, is instead applied as [Action.ApplyRestoredEvidence] - [start]'s own
     * `seedAction`, run BEFORE any journey's `initialState()` (docs/ideen/journey-strategie-
     * vereinheitlichung.md #3) - never through this method, which requires an already-running
     * journey to fire [JourneyEvent.EvidenceReported] against.
     *
     * Called on EVERY evidence-bearing call for [source]: [updates] is the CALLER's complete,
     * currently-valid set for [source] - `AuthEvidence.replaceForSource` drops any existing
     * [source]-owned record whose method is missing here (it expired), so this must run again
     * after every subsequent report, not just once, or a since-expired native method would never
     * actually disappear from the evidence.
     */
    fun applyEvidenceUpdate(journey: AuthJourney, channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        journeyRecorder.mergeEvidence(journey, channel, source, updates)
        advance(journey, channel, JourneyEvent.EvidenceReported)
    }

    // Transitions ----------------------------------------------------------------

    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent): Step {
        val strategy = strategyFor(journey.intent!!)
        val state = codec.read(journey)
        val ctx = contextFor(journey, channel)
        val transition = strategy.transitionErased(state, event, ctx)
        // EvidenceReported fires on every kc-facade upsertChannel call, even a pure re-send of
        // already-known evidence with no floor change (the caller always resends its full current
        // set, never a delta - docs/05-api.md Abschnitt 3) - logging that as if it were a
        // fresh transition would duplicate the SAME "To SelectMethod, candidates X" entry on every
        // poll. Only log it when the transition actually leads somewhere new; a genuine
        // proof/floor-raise always produces a DIFFERENT state (different candidates, or
        // Authenticated), so this never suppresses a real transition.
        val isNoOpEvidenceUpdate = event is JourneyEvent.EvidenceReported && transition is Transition.To && transition.state == state
        if (!isNoOpEvidenceUpdate) {
            // Computed once here and handed to the detail renderer: nextFor stays THE one
            // routing authority ("one function, so the two can never disagree", see nextOf's
            // own doc) - the log only ever shows what routing itself derived.
            val availableTools = availableToolsOf(channel)
            journeyLogService.record(
                channel, journey, event::class.simpleName!!,
                journeyState = state::class.simpleName,
                // acrFloor is what this step was actually judged against; resolvedAcr is the
                // account's own CURRENT combined level from ctx.evidence (MFA-bump included, see
                // DefaultAuthPolicy.resolveAcr) - a single Completed entry's own achievedAcr only
                // ever reflects that ONE tool's individual ceiling (e.g. "loa1" for email alone),
                // so without this the log looks like the login never reached loa2 even when the
                // combination of two loa1 factors just did.
                detail = journeyLogDetails.eventDetail(event) +
                    journeyLogDetails.transitionDetail(transition, journey, channel, state, availableTools) { target ->
                        nextFor(target, availableTools)
                    } +
                    state.logDetail +
                    mapOf("acrFloor" to ctx.acrFloor, "resolvedAcr" to ctx.policy.resolveAcr(ctx.evidence, ctx.account))
            )
        }
        return applyTransition(journey, channel, transition)
    }

    private fun applyTransition(journey: AuthJourney, channel: ChannelSession, transition: Transition): Step = when (transition) {
        is Transition.To -> {
            codec.write(journey, transition.state)
            journeyRepository.save(journey)
            stepFor(transition.state, availableToolsOf(channel))
        }

        is Transition.RequireSubJourney -> {
            // The wish stays parked as this journey's state; SUSPENDED plus the child's
            // parentJourneyId is what keeps "one running journey per channel" true.
            codec.write(journey, transition.resumeWith)
            journey.lifecycle = JourneyLifecycle.SUSPENDED
            journeyRepository.save(journey)
            start(
                channel,
                transition.intent,
                seed = transition.seedWith,
                parentJourneyId = journey.journeyId
            )
        }

        is Transition.Authenticated -> finish(journey, channel)

        is Transition.Perform -> {
            val demoNotice = performAction(journey, channel, transition.action)
            // The one recursive step of the machine: resume at resumeState against a FRESH
            // context (the action just changed evidence/account/whatever it touched) - not the
            // stale one the triggering event arrived with.
            codec.write(journey, transition.resumeState)
            val step = advance(journey, channel, JourneyEvent.ActionCompleted)
            if (demoNotice == null) step else step.copy(stepData = mergeDemoData(step.stepData, demoNotice))
        }

        Transition.Logout -> {
            journey.consume()
            journeyRepository.save(journey)
            journeyLogService.record(channel, journey, "LOGGED_OUT", journeyState = "LoggedOut")
            // The App channel has no browser/cookie of its own to end - but it may hold a real
            // Keycloak session from the custom account-token grant
            // (AccountTokenGrantType's reused session, AuthContext.keycloakSessionId). Ending only
            // THAT one session here keeps "logout means logout" true for this channel, without
            // touching any other session the same account happens to also be logged into
            // elsewhere (e.g. a separate Web-channel browser login). The Web channel's own logout
            // stays entirely Keycloak's (docs/07-betrieb.md Abschnitt 3) - never reaches this
            // transition for that channel.
            if (channel.channel == ChannelSession.Channel.APP) {
                channel.authContextId
                    ?.let { authContextService.getAuthContext(it) }
                    ?.keycloakSessionId
                    ?.let { sessionId -> runCatching { keycloakAdminClient.getIfAvailable()?.logoutSession(sessionId) } }
            }
            channel.authContextId = null
            channel.authEvidenceId = null
            channel.state = ChannelState.LOGGED_OUT
            sessionManagementService.updateChannelSession(channel)
            Step(next = null)
        }

        is Transition.Cancel -> {
            val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
            if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
                // The sub-journey gave up - its parent was only PARKED waiting on it, same
                // handoff as a successful finish().
                markCancelled(journey, channel)
                parent.lifecycle = JourneyLifecycle.STARTED
                journeyRepository.save(parent)
                advance(parent, channel, JourneyEvent.SubJourneyCancelled(journey.intent!!))
            } else {
                // Giving up on the last thing this TOP-LEVEL journey could offer is the same
                // outcome as an explicit DELETE .../journey - unless cancelledTo (via fallBack)
                // already landed the channel back on AUTHENTICATED, in which case there is
                // nothing to restart (same guard as ChannelService.cancelActiveJourney).
                cancel(journey, channel)
                if (channel.state == ChannelState.AUTHENTICATED) Step(Next.AUTHENTICATED) else startEntryJourney(channel)
            }
        }

        is Transition.Abort -> {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted(transition.reason)
        }
    }

    /** Folds a [performAction] demo notice into the already-computed next step's own `demo` block, if any. */
    private fun mergeDemoData(stepData: Map<String, Any?>?, notice: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val existingDemo = stepData?.get(DEMO_DATA_KEY) as? Map<String, Any?>
        return stepData.orEmpty() + (DEMO_DATA_KEY to (existingDemo.orEmpty() + notice))
    }

    /**
     * The [Action]s a [Transition.Perform] can carry, actually executed. The first four
     * (tool-outcome) variants carry their own [ToolDescriptor]/[ToolOutcome] (see [Action]'s own
     * doc), so [recordToolCompletion] - the MethodEvidence bookkeeping every one of them needs
     * alike - reads it straight off the action instead of needing it passed in separately.
     *
     * @return a demo-only notice to merge into the eventual response's `demo` block (see
     * [DEMO_DATA_KEY]), or `null` when this action has none. Currently only [Action.AdoptCredential]
     * ever returns one - see [performAdoptCredential] for why.
     */
    private fun performAction(journey: AuthJourney, channel: ChannelSession, action: Action): Map<String, Any?>? {
        var demoNotice: Map<String, Any?>? = null
        when (action) {
            is Action.AdoptIdentity -> performAdoptIdentity(journey, channel, action)
            is Action.ConfirmIdentity -> performConfirmIdentity(journey, channel, action)
            is Action.AdoptCredential -> demoNotice = performAdoptCredential(journey, channel, action)
            is Action.AcceptProof -> performAcceptProof(journey, channel, action)
            is Action.ApplyRestoredEvidence ->
                journeyRecorder.mergeEvidence(journey, channel, action.source, action.methods)
            // The tool already performed its own domain effect (writing QrLoginRequest) before
            // reporting Approved - nothing left to do here beyond the same bookkeeping every
            // tool-outcome action gets. achievedAcr/amr are empty by construction (see
            // ToolOutcome.Completed.Approved's own doc), so this never changes what the channel's
            // own evidence says it has proven.
            is Action.RecordApproval -> journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
            is Action.Remove -> removeMethod(journey, channel, action.methodInstanceId)
            is Action.LinkDevice -> performLinkDevice(channel, action)
            is Action.DeleteAccount -> performDeleteAccount(journey, channel, action)
        }
        return demoNotice
    }

    /**
     * Central identity resolution (docs/ideen/claims-modell-und-vertrauensanker.md,
     * "Identitaetsauflösung & Matching"): the account module owns the matching policy, this
     * service only governs the consequences.
     */
    private fun performAdoptIdentity(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptIdentity) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val resolution = identityResolver.resolve(action.outcome.claims.toSet())
        val accountId = when (resolution) {
            is Resolution.ExistingAccount -> resolution.accountId
            // The account and its claims share this journey transaction, including rollback.
            Resolution.NewInteressent -> accountService.createUnidentifiedAccount().accountId
            is Resolution.Ambiguous -> throw OrchestratorException.invalidState(
                "Identifizierung mehrdeutig: ${resolution.candidateCount} Kandidaten - keine automatische Zuordnung"
            )
            // Deliberately no persistent candidate log here: this aborts the journey
            // transaction anyway, and the real policy for ambiguous matches (offer a
            // stronger procedure, human review) arrives with the first EUDI case.
        }
        bindAccount(journey, channel, accountId)
        accountService.recordClaims(accountId, action.outcome.claims)
        journeyRecorder.recordIdentification(journey, channel, action.tool, action.outcome)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    private fun performConfirmIdentity(journey: AuthJourney, channel: ChannelSession, action: Action.ConfirmIdentity) {
        assertClaimsCovered(action.tool, action.outcome.claims)
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) {
            "Identified without a known account under ${journey.intent}"
        }
        when (val resolution = identityResolver.resolve(action.outcome.claims.toSet())) {
            is Resolution.ExistingAccount ->
                if (resolution.accountId != accountId) {
                    throw IdentityConflictException("Identification claims resolve to a different account")
                }
            // A known account may still be an unbound enrollment-first account. In that case
            // resolution has no existing anchor to return yet; the shared recordClaims call
            // below performs the first immutable PERSON_ID binding.
            Resolution.NewInteressent -> Unit
            is Resolution.Ambiguous ->
                throw OrchestratorException.invalidState(
                    "Identifizierung mehrdeutig: ${resolution.candidateCount} Kandidaten - keine automatische Zuordnung"
                )
        }
        bindAccount(journey, channel, accountId)
        accountService.recordClaims(accountId, action.outcome.claims)
        journeyRecorder.recordIdentification(journey, channel, action.tool, action.outcome)
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
    }

    private fun performAdoptCredential(journey: AuthJourney, channel: ChannelSession, action: Action.AdoptCredential): Map<String, Any?>? {
        val enrolled = action.outcome
        // Same declaration check as the identity branches - the descriptor↔handler
        // contract holds for every adopting tool, and the claims are recorded right
        // below.
        assertClaimsCovered(action.tool, enrolled.claims)
        // Enrollment with no account yet (REGISTER "Enrollment zuerst",
        // docs/04-orchestrierung.md) - one is created lazily, right here, on the FIRST
        // completed enrollment: no enroll tool's own PATCH handler needs an account to
        // already exist mid-flow, so this is always safe to defer to here. A channel
        // that never gets this far leaves no orphan account behind
        // (`deleteIfAbandonedUnidentified`, `fallBack`).
        val accountId = journey.accountId ?: channel.accountId
            ?: accountService.createUnidentifiedAccount().accountId.also { bindAccount(journey, channel, it) }
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Enrolled without an AuthEvidence" }
        val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
            "AuthEvidence not found: $authEvidenceId"
        }
        val coreEvidence = evidence.toCoreEvidence()
        // `label` is lifted into its own field rather than staying in the generic details
        // blob, so the API can surface it without clients reaching into details.
        val label = enrolled.auditDetails?.get("label") as? String
        // Every claim this enrollment asserted lands in the account's identity log
        // (AccountService.recordClaims); an EMAIL claim additionally consolidates its
        // anchor and fires the AccountChanged event. Done
        // here, before this method returns, so the very next context rebuild
        // (JourneyEvent.ActionCompleted) already sees it.
        accountService.recordClaims(accountId, enrolled.claims)
        // What the environment already established BEFORE this completion (recordToolCompletion
        // for THIS one hasn't run yet). "none" only ever means literally nothing backs this
        // session yet (REGISTER "Enrollment zuerst" with no identification at all,
        // docs/04-orchestrierung.md) - falls back to the flat baseline floor, never to this
        // tool's OWN declared strength: a self-registered credential with nothing else
        // corroborating it (no identification, no other factor) is exactly the "self-
        // asserted, unproofed" case, regardless of how strong the tool's own maxAcr
        // theoretically is (e.g. enroll-device declares loa2 on its own - letting that
        // stand unchallenged here would grant loa2 to a device nobody ever verified).
        // Never allowed to override an already-positive base either way: doing that
        // unconditionally (e.g. via a "projected" self-entry merged into the evidence
        // before resolving) would let a tool with a higher maxAcr than the CURRENT session
        // actually proved (that same enroll-device, enrolled from a loa1-only session)
        // silently escalate past what was ever really established - exactly the
        // self-escalation ADR-5 exists to prevent.
        val environmentAcr = authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId))
        val enrolledUnderAcr = if (environmentAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else environmentAcr
        // Demo-only transparency for the ADR-5 cap above: this tool's own maxAcr promises
        // more than the session had actually established, so the credential just created is
        // quietly weaker than its catalog entry suggests - visible here once, at the moment
        // it happens, rather than only discoverable later as a confusing STEP_UP/CONFIRM_
        // PEER_LOGIN rejection with no obvious cause (docs/04-orchestrierung.md #8).
        val demoNotice =
            if (AcrLevel.rank(enrolledUnderAcr) < AcrLevel.rank(action.tool.maxAcr)) {
                mapOf(
                    "enrolledUnderAcrCapped" to mapOf(
                        "toolId" to action.tool.toolId,
                        "enrolledUnderAcr" to enrolledUnderAcr,
                        "toolMaxAcr" to action.tool.maxAcr
                    )
                )
            } else null
        accountService.addAuthenticationMethod(
            accountId,
            action.tool.method,
            enrolled.enrollmentRef,
            enrolledUnderAcr = enrolledUnderAcr.value,
            details = enrolled.auditDetails.orEmpty().minus("label") + mapOf(
                "enrolledUnderAmr" to evidence.currentAmr,
                "channel" to channel.channel?.name
            ),
            allowsMultipleInstances = action.tool.allowsMultipleInstances,
            label = label
        )
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed, not just incidentally skipped by a null bindingKeyRef.
        if (action.bindDevice && channel.channel == ChannelSession.Channel.APP) {
            sessionManagementService.linkDeviceToAccount(
                checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" },
                accountId
            )
        }
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
        return demoNotice
    }

    private fun performAcceptProof(journey: AuthJourney, channel: ChannelSession, action: Action.AcceptProof) {
        val authenticated = action.outcome
        val resolved = authenticated.accountId.takeIf { action.useOutcomeAccount }
        val accountId = checkNotNull(resolved ?: journey.accountId ?: channel.accountId) {
            "Authenticated without a known account"
        }
        bindAccount(journey, channel, accountId)
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed, not just incidentally skipped by a null bindingKeyRef.
        if (action.bindDevice && channel.channel == ChannelSession.Channel.APP) {
            sessionManagementService.linkDeviceToAccount(
                checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" },
                accountId
            )
        }
        val used = checkNotNull(accountService.findActiveMethod(accountId, action.tool.method)) {
            "No active method '${action.tool.method}' for account $accountId"
        }
        val effectiveAcr = AcrLevel.min(authenticated.achievedAcr, used.enrolledUnderAcr?.let(AcrLevel::of))
        journeyRecorder.recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
    }

    private fun performLinkDevice(channel: ChannelSession, action: Action.LinkDevice) {
        // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
        // suppressed rather than left to a null bindingKeyRef, so a KEYCLOAK channel never
        // accumulates dead DeviceAccountLink rows even if a strategy ever offered this.
        if (channel.channel == ChannelSession.Channel.APP) {
            val bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" }
            val previousAccountId = sessionManagementService.findLinkedAccountId(bindingKeyRef)
            sessionManagementService.linkDeviceToAccount(bindingKeyRef, action.accountId)
            // A device is only ever actively bound to one account at a time - once rebound
            // (docs/04-orchestrierung.md #2, RegisterState.ConfirmDeviceRebind), the previous
            // account's own device-bound credential(s) for this exact physical key must not
            // keep working (docs/09-dpop.md); Phase 1's matching guard already hides them, this
            // additionally removes them outright.
            if (previousAccountId != null && previousAccountId != action.accountId) {
                accountService.findAccount(previousAccountId)?.activeAuthenticationMethods
                    ?.filter { m ->
                        val descriptor = toolRegistry.descriptors().firstOrNull { it.role == MethodRole.IDENTIFIED_AUTH && it.method == m.method }
                        descriptor != null && descriptor.allowsMultipleInstances && descriptor.matchesCaller(m.details, bindingKeyRef)
                    }
                    ?.forEach { accountDeletionService.revokeMethod(previousAccountId, checkNotNull(it.id) { "Active method without an id" }) }
            }
        }
    }

    private fun performDeleteAccount(journey: AuthJourney, channel: ChannelSession, action: Action.DeleteAccount) {
        // Independent re-check against freshly derived context, not the strategy's own
        // state - same reasoning as the self-lockout check before Action.Remove.
        val freshCtx = contextFor(journey, channel)
        val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        check(authPolicy.isSatisfied(freshCtx.evidence, requiredAcr, account)) {
            "${journey.intent} decided Action.DeleteAccount without satisfying $requiredAcr"
        }
        accountDeletionService.deleteAccount(action.accountId)
    }

    private fun finish(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume()
        journeyRepository.save(journey)

        val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
        if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
            // The parent picks up exactly where it was parked, so the original wish survives.
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyFinished(journey.intent!!, currentAcrOf(channel)))
        }

        channel.state = ChannelState.AUTHENTICATED
        sessionManagementService.updateChannelSession(channel)
        return Step(Next.AUTHENTICATED)
    }

    /**
     * The one action a strategy may ask for that is not a tool run. Guarded against self-lockout:
     * rejected if the account could no longer reach its own channel's floor afterwards.
     */
    private fun removeMethod(journey: AuthJourney, channel: ChannelSession, methodInstanceId: String) {
        val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Remove without a known account" }
        val account = accountService.findAccount(accountId)
            ?: throw OrchestratorException.processGone("Account not found: $accountId")
        val target = account.authenticationMethods.firstOrNull { it.active && it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound("No active method '$methodInstanceId' for this account")

        val afterRemoval = account.copy(
            authenticationMethods = account.authenticationMethods.map {
                if (it.id == methodInstanceId) it.copy(active = false) else it
            }
        )
        if (authPolicy.reachability(afterRemoval, acrFloorOf(channel)) !is Reachability.Reachable) {
            throw OrchestratorException.invalidState(
                "Deaktivieren von '${target.method}' wuerde das Mindestniveau dieses Kanals unterschreiten"
            )
        }
        accountService.deactivateAuthenticationMethod(accountId, methodInstanceId)
    }

    /**
     * The attempt budget spans the whole journey (docs/04-orchestrierung.md #7). A failed attempt
     * with budget left is not an HTTP error - it behaves like missing input; only an exhausted
     * budget ends things terminally, and it ends the JOURNEY, not just the current state.
     */
    private fun chargeAttempt(journey: AuthJourney, channel: ChannelSession, tool: ToolDescriptor, outcome: ToolOutcome.Failed): Step {
        journey.attemptBudget -= 1
        journeyLogService.record(
            channel, journey, "TOOL_FAILED",
            journeyState = codec.read(journey)::class.simpleName,
            detail = mapOf(
                "toolId" to tool.toolId,
                "reason" to outcome.reason,
                "attemptedAccountId" to outcome.attemptedAccountId,
                "attemptedPersonId" to outcome.attemptedPersonId,
                "attemptBudgetLeft" to journey.attemptBudget
            )
        )
        if (journey.attemptBudget <= 0) {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted("Retry-Limit erreicht: ${outcome.reason}")
        }
        journeyRepository.save(journey)
        return Step(nextOf(journey, channel), mapOf("error" to outcome.reason))
    }

    /**
     * Both channel types get an [AuthEvidence] here - the shared evidence trail, regardless of
     * facade. Only the APP channel additionally gets an [AuthContext] (docs/ideen/web-keycloak-
     * kanal.md #6): token issuance is App-exclusive, KEYCLOAK never calls `getToken`/`getIdClaims`
     * - the one deliberate, documented channel-type branch in this method.
     */
    private fun bindAccount(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        journey.accountId = accountId
        channel.accountId = accountId
        if (channel.authEvidenceId == null) {
            // Fresh login: start a new evidence trail rather than reuse a stale one.
            val evidenceId = checkNotNull(authEvidenceService.createForAccount(accountId).authEvidenceId)
            channel.authEvidenceId = evidenceId
            if (channel.channel == ChannelSession.Channel.APP) {
                channel.authContextId = authContextService.createForAccount(accountId, evidenceId).authContextId
            }
        }
        sessionManagementService.updateChannelSession(channel)
        journeyRepository.save(journey)
    }

    // Cancellation fallout -------------------------------------------------------

    /**
     * Where the channel lands after an abandoned journey. Account and evidence are re-derived from
     * the DURABLE truth rather than blindly kept or blindly wiped: the device link is what
     * survives a journey, an AuthContext is not.
     */
    private fun fallBack(journey: AuthJourney, channel: ChannelSession) {
        val strategy = strategyFor(journey.intent!!)
        val target = strategy.cancelledToErased(codec.read(journey))
        channel.state = target
        if (target != ChannelState.AUTHENTICATED) {
            channel.authContextId = null
            channel.authEvidenceId = null
            val abandonedAccountId = channel.accountId
            channel.accountId = if (channel.entryIntent == AuthIntent.FAST_ACCESS) {
                sessionManagementService.findLinkedAccountId(channel.bindingKeyRef!!)
            } else {
                null
            }
            deleteIfAbandonedUnidentified(abandonedAccountId)
        }
        sessionManagementService.updateChannelSession(channel)
    }

    /**
     * A REGISTER "Enrollment zuerst" account (docs/04-orchestrierung.md, `Action.
     * CreateUnidentifiedAccount`) is created eagerly, before the first enrollment tool even runs -
     * if the journey is then abandoned before anything durable ever attached to it, nothing should
     * be left behind. Safe as a generic check for every intent alike, not just REGISTER: no other
     * flow ever leaves an account with neither a person nor a single authentication method.
     */
    private fun deleteIfAbandonedUnidentified(accountId: Long?) {
        if (accountId == null) return
        val account = accountService.findAccount(accountId) ?: return
        if (account.personId == null && account.authenticationMethods.isEmpty()) {
            accountService.deleteAccount(accountId)
        }
    }

    // Context ---------------------------------------------------------------------

    private fun contextFor(journey: AuthJourney, channel: ChannelSession): JourneyContext {
        val accountId = journey.accountId ?: channel.accountId
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        return JourneyContext(
            channel = checkNotNull(channel.channel) { "ChannelSession without a channel type" },
            account = accountId?.let { accountService.findAccount(it) },
            evidence = evidence?.toCoreEvidence() ?: AuthEvidence(emptyList()),
            acrFloor = acrFloorOf(channel),
            bindingKeyRef = channel.bindingKeyRef,
            linkedAccountId = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) },
            isSubJourney = journey.parentJourneyId != null,
            policy = authPolicy,
            catalog = toolRegistry,
            availableTools = availableToolsOf(channel),
            featureFlags = featureFlagProviders.flatMapTo(mutableSetOf()) { it.activeFlags() }
        )
    }

    private fun acrFloorOf(channel: ChannelSession): AcrLevel = channel.acrFloor?.let(AcrLevel::of) ?: AcrLevels.DEFAULT_REQUIRED_ACR

    /** Live, not cached (docs/orchestrator/policy/AuthEvidence.kt): `currentAcr` is never stored, only ever recomputed from the evidence that's actually there. */
    private fun currentAcrOf(channel: ChannelSession): AcrLevel {
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) } ?: return AcrLevel.NONE
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return authPolicy.resolveAcr(evidence.toCoreEvidence(), account)
    }

    private fun strategyFor(intent: AuthIntent): IntentStrategy<*> =
        strategiesByIntent[intent] ?: error("No IntentStrategy for $intent")

    /**
     * The one place the SPI's state type is erased. A strategy is only ever handed the state of
     * its own intent - the journey's `intent` column and [JourneyStateCodec.read] guarantee that
     * together - but the registry is necessarily heterogeneous, so the cast lives here once
     * instead of in every strategy. Named with an `Erased` suffix, not the interface's own method
     * names, so a call site never reads like (non-existent) recursion.
     */
    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.transitionErased(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        (this as IntentStrategy<JourneyState>).transition(state, event, ctx)

    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.cancelledToErased(state: JourneyState): ChannelState =
        (this as IntentStrategy<JourneyState>).cancelledTo(state)

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
