package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.journey.state.ToolRef
import com.example.dpop.tool_api.JourneyDebugStep
import com.example.dpop.tool_api.Next
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.policy.MethodName
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.AcrLevel
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
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
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
    private val authContextService: AuthContextService,
    private val authEvidenceService: AuthEvidenceService,
    private val sessionManagementService: SessionManagementService,
    private val toolRegistry: ToolHandlerRegistry,
    private val authPolicy: AuthPolicy,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val accountDeletionService: AccountDeletionService,
    private val journeyLogService: JourneyLogService,
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
            journeyLogService.record(channel, journey, "Entry", detail = actionDetail(action, journey, channel))
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
     * Starts [intent] seeded toward [targetAcr] - the same seed a sub-journey of this intent would
     * get ([IntentStrategy.initialStateForSubJourneyAcr]), just entered directly (e.g. the App
     * channel's own step-up trigger) instead of as another journey's precondition.
     */
    fun startTowardAcr(channel: ChannelSession, intent: AuthIntent, targetAcr: String, startingAcr: String): Step =
        start(channel, intent, seed = strategyFor(intent).initialStateForSubJourneyAcr(targetAcr, startingAcr))

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

    private fun nextFor(state: JourneyState, availableTools: Set<String>): Next {
        state.active?.let { return Next.tool(it.toolId, it.step, it.toolSessionId) }
        val activatable = state.activatable(availableTools)
        return if (activatable.size == 1) {
            val toolId = activatable.single()
            Next.tool(toolId, toolRegistry.descriptorOf(toolId).startStep)
        } else {
            // Several candidates open a selection page; zero means an orchestrator-owned page
            // that isn't a choice at all (a confirmation, the finished screen), or a state whose
            // only offer just became unavailable - the empty option list resolves itself once the
            // client's next action (abandon/activate) drives an actual transition.
            Next.orchestrator(state.selectionContext, state.selectionStep)
        }
    }

    private fun stepFor(state: JourneyState, availableTools: Set<String>): Step {
        val options = state.activatable(availableTools)
        val stepData = buildMap<String, Any?> {
            if (state is OfferingState && options.size > 1) {
                put("options", options.toList())
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
    private fun availableToolsOf(channel: ChannelSession): Set<String> =
        channel.availableClientTools - toolAvailabilityService.disabledToolIds()

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

    fun isCurrent(journey: AuthJourney, toolId: String, toolSessionId: UUID): Boolean =
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
            Step(Next.tool(tool.toolId, outcome.nextStep, active.toolSessionId), outcome.data)
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
        mergeEvidence(journey, channel, source, updates)
        advance(journey, channel, JourneyEvent.EvidenceReported)
    }

    /**
     * Takes the REAL `MethodEvidence` list, not several per-field maps keyed by method - the
     * caller already has a complete record per method by the time it calls this.
     */
    private fun mergeEvidence(journey: AuthJourney, channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        // [source]'s set BEFORE the update - compared against [updates] below to decide whether
        // this call actually changed anything worth logging. Needed because every caller resends
        // its COMPLETE current set on every call, no delta (docs/05-api.md Abschnitt 3,
        // "kein Delta") - e.g. every LoA-2 selectMethod poll re-reports the very same native
        // password proof, and logging that identically on each poll would drown the one real entry
        // (the first time it was proven) in noise.
        val before = channel.authEvidenceId
            ?.let { authEvidenceService.getAuthEvidence(it) }?.amrEvidence.orEmpty()
            .filter { it.source == source }
            .map { Triple(it.method, it.loa, it.amrSourceId) }
            .toSet()
        val after = updates.map { Triple(it.method.value, it.loa.value, it.amrSourceId) }.toSet()

        authEvidenceService.attachToChannel(channel, source, updates)
        sessionManagementService.recordEvent(channel.channelSessionId, journey.journeyId, "EVIDENCE_UPDATE_APPLIED", source)
        if (before != after) {
            // The generic advance() call right after this logs the EvidenceReported transition
            // itself (with acrFloor/resolvedAcr), but not WHAT changed - this records that
            // (docs/05-api.md Abschnitt 3: native/external evidence, source=kc) - without
            // it, the journey log would show every tool outcome in full but go silent on every
            // Keycloak-native factor, even though it's just as real a step in the journey's path.
            // snake_case, not PascalCase: JourneyService buckets this at the machine's discretion,
            // not as a real `event::class.simpleName` transition (naming convention, docs/ideen/
            // journey-strategie-vereinheitlichung.md #4) - and deliberately not named similarly to
            // "EvidenceReported" (the real transition's own log entry), which used to invite
            // confusing the two.
            journeyLogService.record(
                channel, journey, "native_evidence_synced",
                journeyState = codec.read(journey)::class.simpleName,
                detail = mapOf("source" to source, "methods" to methodEvidenceDetail(updates))
            )
        }
    }

    private fun methodEvidenceDetail(methods: List<MethodEvidence>): List<Map<String, Any?>> =
        methods.map { mapOf("method" to it.method.value, "loa" to it.loa.value, "factorTypes" to it.factorTypes.map { t -> t.name }, "amrSourceId" to it.amrSourceId) }

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
            journeyLogService.record(
                channel, journey, event::class.simpleName!!,
                journeyState = state::class.simpleName,
                // acrFloor is what this step was actually judged against; resolvedAcr is the
                // account's own CURRENT combined level from ctx.evidence (MFA-bump included, see
                // DefaultAuthPolicy.resolveAcr) - a single Completed entry's own achievedAcr only
                // ever reflects that ONE tool's individual ceiling (e.g. "loa1" for email alone),
                // so without this the log looks like the login never reached loa2 even when the
                // combination of two loa1 factors just did.
                detail = eventDetail(event) + transitionDetail(transition, journey, channel, state) + state.logDetail +
                    mapOf("acrFloor" to ctx.acrFloor, "resolvedAcr" to ctx.policy.resolveAcr(ctx.evidence, ctx.account))
            )
        }
        return applyTransition(journey, channel, transition)
    }

    /** The extra, event-specific detail worth keeping in the JourneyLog - which tool was involved, and how the outcome/answer read. */
    private fun eventDetail(event: JourneyEvent): Map<String, Any?> = when (event) {
        is JourneyEvent.Completed -> mapOf("toolId" to event.tool.toolId, "method" to event.tool.method) + outcomeDetail(event.outcome)
        is JourneyEvent.Abandoned -> mapOf("toolId" to event.tool.toolId)
        is JourneyEvent.Answered -> mapOf("answer" to event.answer)
        is JourneyEvent.SubJourneyFinished -> mapOf("subIntent" to event.intent.name, "achievedAcr" to event.achievedAcr)
        is JourneyEvent.SubJourneyCancelled -> mapOf("subIntent" to event.intent.name)
        JourneyEvent.Started -> emptyMap()
        JourneyEvent.EvidenceReported -> emptyMap()
        JourneyEvent.ActionCompleted -> emptyMap()
    }

    /** Everything a completed tool run determined - the variant-specific fields, not just the common amr/achievedAcr/factorTypes. */
    private fun outcomeDetail(outcome: ToolOutcome.Completed): Map<String, Any?> {
        val common = mapOf(
            "outcome" to outcome::class.simpleName,
            "amr" to outcome.amr,
            "achievedAcr" to outcome.achievedAcr,
            "factorTypes" to outcome.factorTypes.map { it.name }
        )
        val specific = when (outcome) {
            is ToolOutcome.Completed.Identified -> mapOf("personId" to outcome.personId)
            is ToolOutcome.Completed.Enrolled -> mapOf("enrollmentRef" to outcome.enrollmentRef.toString())
            is ToolOutcome.Completed.Authenticated -> mapOf("accountId" to outcome.accountId)
            is ToolOutcome.Completed.Approved -> emptyMap()
        }
        return common + specific
    }

    /**
     * Where the transition leads - the concrete follow-up (target state/sub-intent/action), not
     * just which [Transition] variant fired. For [Transition.To], resolves the actual next
     * tool(s) via [toolRegistry] the same way [nextFor] does, so the log shows what the client
     * will see, not just the internal state-class name.
     */
    private fun transitionDetail(transition: Transition, journey: AuthJourney, channel: ChannelSession, state: JourneyState): Map<String, Any?> = when (transition) {
        is Transition.To -> {
            val availableTools = availableToolsOf(channel)
            val candidates = transition.state.activatable(availableTools)
            val next = nextFor(transition.state, availableTools)
            mapOf(
                "decision" to "To",
                "toState" to transition.state::class.simpleName,
                "candidateTools" to candidates.map { toolId -> toolId to toolRegistry.descriptorOf(toolId).method }.toMap(),
                "next" to mapOf("type" to next.type, "toolId" to next.toolId, "context" to next.context, "step" to next.step)
            )
        }
        is Transition.RequireSubJourney -> mapOf(
            "decision" to "RequireSubJourney", "subIntent" to transition.intent.name, "targetAcr" to transition.targetAcr
        )
        // [state] is whatever was active right BEFORE this transition - e.g. the RestoreData
        // Anfangs-Übergang can leave a fresh channel Authenticated on its very first Started, with
        // no intervening `To` entry to show what could still have been offered. Without
        // candidateTools here, that case's log would go from "here's the seeded evidence" straight
        // to "Authenticated" with no trace of what else was available - misleadingly emptier than
        // the App channel's own Authenticated log entries ever were.
        Transition.Authenticated -> mapOf(
            "decision" to "Authenticated",
            "candidateTools" to state.activatable(availableToolsOf(channel))
                .map { toolId -> toolId to toolRegistry.descriptorOf(toolId).method }.toMap()
        )
        is Transition.Perform -> mapOf("decision" to "Perform", "action" to transition.action::class.simpleName) +
            actionDetail(transition.action, journey, channel)
        Transition.Logout -> mapOf("decision" to "Logout")
        Transition.Cancel -> mapOf("decision" to "Cancel")
        is Transition.Abort -> mapOf("decision" to "Abort", "reason" to transition.reason)
    }

    /**
     * What an [Action] carries worth keeping in the log - the four tool-outcome actions'
     * `useOutcomeAccount`/`bindDevice` (fachlich verschiedene Fälle wie "Account neu aufgelöst"
     * vs. "nur bestätigt" sind sonst trotz identischem `toolId`/`outcome` ununterscheidbar), and
     * for [Action.Remove] which method/account it actually names - the class name alone
     * ("Remove") doesn't say which one. Shared between the "Entry" seedAction log line and every
     * [Transition.Perform] logged in [advance].
     */
    private fun actionDetail(action: Action, journey: AuthJourney, channel: ChannelSession): Map<String, Any?> = when (action) {
        is Action.AdoptIdentity, is Action.ConfirmIdentity -> emptyMap()
        is Action.AdoptCredential -> mapOf("bindDevice" to action.bindDevice)
        is Action.AcceptProof -> mapOf("useOutcomeAccount" to action.useOutcomeAccount, "bindDevice" to action.bindDevice)
        is Action.RecordApproval -> emptyMap()
        is Action.ApplyRestoredEvidence -> mapOf("source" to action.source, "methods" to methodEvidenceDetail(action.methods))
        is Action.Remove -> {
            val accountId = journey.accountId ?: channel.accountId
            val target = accountId?.let { accountService.findAccount(it) }
                ?.authenticationMethods?.firstOrNull { it.id == action.methodInstanceId }
            mapOf("methodInstanceId" to action.methodInstanceId, "method" to target?.method, "label" to target?.label)
        }
        is Action.LinkDevice -> mapOf("accountId" to action.accountId)
        is Action.DeleteAccount -> mapOf("accountId" to action.accountId)
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
                seed = seedFor(transition, channel),
                parentJourneyId = journey.journeyId
            )
        }

        is Transition.Authenticated -> finish(journey, channel)

        is Transition.Perform -> {
            performAction(journey, channel, transition.action)
            // The one recursive step of the machine: resume at resumeState against a FRESH
            // context (the action just changed evidence/account/whatever it touched) - not the
            // stale one the triggering event arrived with.
            codec.write(journey, transition.resumeState)
            advance(journey, channel, JourneyEvent.ActionCompleted)
        }

        Transition.Logout -> {
            journey.consume()
            journeyRepository.save(journey)
            journeyLogService.record(channel, journey, "LOGGED_OUT", journeyState = "LoggedOut")
            // The App channel has no browser/cookie of its own to end - but it may hold a real
            // Keycloak session from the custom account-token grant (DPoP-demo-xso,
            // AccountTokenGrantType's reused session, AuthContext.keycloakSessionId). Ending only
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

    /**
     * The [Action]s a [Transition.Perform] can carry, actually executed. The first four
     * (tool-outcome) variants carry their own [ToolDescriptor]/[ToolOutcome] (see [Action]'s own
     * doc), so [recordToolCompletion] - the MethodEvidence bookkeeping every one of them needs
     * alike - reads it straight off the action instead of needing it passed in separately.
     */
    private fun performAction(journey: AuthJourney, channel: ChannelSession, action: Action) {
        when (action) {
            is Action.AdoptIdentity -> {
                val account = accountService.findOrCreateAccount(action.outcome.personId)
                bindAccount(journey, channel, account.accountId)
                recordIdentification(journey, channel, action.tool, action.outcome)
                recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
            }

            is Action.ConfirmIdentity -> {
                val accountId = checkNotNull(journey.accountId ?: channel.accountId) {
                    "Identified without a known account under ${journey.intent}"
                }
                val account = checkNotNull(accountService.findAccount(accountId)) { "Account not found: $accountId" }
                if (account.personId != action.outcome.personId) {
                    throw OrchestratorException.invalidState("Identifizierte Person passt nicht zum angemeldeten Konto")
                }
                bindAccount(journey, channel, accountId)
                recordIdentification(journey, channel, action.tool, action.outcome)
                recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)
            }

            is Action.AdoptCredential -> {
                val enrolled = action.outcome
                val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Enrolled without an account" }
                val authEvidenceId = checkNotNull(channel.authEvidenceId) { "Enrolled without an AuthEvidence" }
                val evidence = checkNotNull(authEvidenceService.getAuthEvidence(authEvidenceId)) {
                    "AuthEvidence not found: $authEvidenceId"
                }
                val coreEvidence = evidence.toCoreEvidence()
                // `label` is lifted into its own field rather than staying in the generic details
                // blob, so the API can surface it without clients reaching into details.
                val label = enrolled.auditDetails?.get("label") as? String
                accountService.addAuthenticationMethod(
                    accountId,
                    action.tool.method,
                    enrolled.enrollmentRef,
                    enrolledUnderAcr = authPolicy.resolveAcr(coreEvidence, accountService.findAccount(accountId)),
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
                recordToolCompletion(journey, channel, action.tool, enrolled, enrolled.achievedAcr)
            }

            is Action.AcceptProof -> {
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
                val effectiveAcr = AcrLevels.min(authenticated.achievedAcr, used.enrolledUnderAcr)
                recordToolCompletion(journey, channel, action.tool, authenticated, effectiveAcr)
            }

            is Action.ApplyRestoredEvidence ->
                mergeEvidence(journey, channel, action.source, action.methods)

            // The tool already performed its own domain effect (writing QrLoginRequest) before
            // reporting Approved - nothing left to do here beyond the same bookkeeping every
            // tool-outcome action gets. achievedAcr/amr are empty by construction (see
            // ToolOutcome.Completed.Approved's own doc), so this never changes what the channel's
            // own evidence says it has proven.
            is Action.RecordApproval -> recordToolCompletion(journey, channel, action.tool, action.outcome, action.outcome.achievedAcr)

            is Action.Remove -> removeMethod(journey, channel, action.methodInstanceId)

            // KEYCLOAK has no device to link (docs/02-domaenenmodell.md Abschnitt 1) - actively
            // suppressed rather than left to a null bindingKeyRef, so a KEYCLOAK channel never
            // accumulates dead DeviceAccountLink rows even if a strategy ever offered this.
            is Action.LinkDevice -> if (channel.channel == ChannelSession.Channel.APP) {
                sessionManagementService.linkDeviceToAccount(
                    checkNotNull(channel.bindingKeyRef) { "APP channel without a bindingKeyRef" },
                    action.accountId
                )
            }

            is Action.DeleteAccount -> {
                // Independent re-check against freshly derived context, not the strategy's own
                // state - same reasoning as the self-lockout check before Action.Remove.
                val freshCtx = contextFor(journey, channel)
                val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
                check(authPolicy.isSatisfied(freshCtx.evidence, Action.DeleteAccount.REQUIRED_ACR, account)) {
                    "${journey.intent} decided Action.DeleteAccount without satisfying ${Action.DeleteAccount.REQUIRED_ACR}"
                }
                accountDeletionService.deleteAccount(action.accountId)
            }
        }
    }

    /**
     * The MethodEvidence bookkeeping every tool-outcome [Action] needs alike, once: the cap
     * `min(achievedAcr, enrolledUnderAcr)` a caller already folded into [effectiveAcr] where it
     * applies ([Action.AcceptProof]) lives ONLY there - here just records what the outcome proved,
     * at whatever level the caller decided actually counts.
     */
    private fun recordToolCompletion(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed,
        effectiveAcr: String?
    ) {
        val authEvidenceId = checkNotNull(channel.authEvidenceId) { "AuthEvidence missing after ${tool.toolId}" }
        val accountId = channel.accountId
        val updates = outcome.amr.map { method ->
            MethodEvidence(
                method = MethodName(method),
                // This run's own achieved/capped level if it has one, else the tool's own declared ceiling.
                loa = AcrLevel(effectiveAcr ?: tool.maxAcr),
                // The account's own enrollment record for this method (docs/06-ablaeufe.md #1)
                // - the same idiom Action.AcceptProof already reads.
                enrolledUnderAcr = accountId?.let { accountService.findActiveMethod(it, method)?.enrolledUnderAcr }?.let(::AcrLevel),
                factorTypes = outcome.factorTypes,
                source = AmrSource.ORCHESTRATOR,
                amrSourceId = tool.toolId,
            )
        }
        authEvidenceService.applyEvidence(authEvidenceId, updates)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "TOOL_COMPLETED:${tool.toolId}", "orchestrator"
        )
    }

    private fun seedFor(transition: Transition.RequireSubJourney, channel: ChannelSession): JourneyState =
        strategyFor(transition.intent).initialStateForSubJourneyAcr(transition.targetAcr, currentAcrOf(channel))

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
        if (!authPolicy.canAccountReach(afterRemoval, acrFloorOf(channel))) {
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

    private fun recordIdentification(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed.Identified
    ) {
        accountService.addIdentification(
            checkNotNull(journey.accountId),
            tool.method,
            outcome.achievedAcr,
            outcome.auditDetails.orEmpty() + mapOf(
                "channel" to channel.channel?.name,
                "journeyId" to journey.journeyId.toString()
            )
        )
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
            channel.accountId = if (channel.entryIntent == AuthIntent.FAST_ACCESS) {
                sessionManagementService.findLinkedAccountId(channel.bindingKeyRef!!)
            } else {
                null
            }
        }
        sessionManagementService.updateChannelSession(channel)
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
            availableTools = availableToolsOf(channel)
        )
    }

    private fun acrFloorOf(channel: ChannelSession): String = channel.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR

    /** Live, not cached (docs/orchestrator/policy/AuthEvidence.kt): `currentAcr` is never stored, only ever recomputed from the evidence that's actually there. */
    private fun currentAcrOf(channel: ChannelSession): String {
        val evidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) } ?: return "none"
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
