package com.example.dpop.orchestrator.journey

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.journey.state.AnswerableState
import com.example.dpop.orchestrator.journey.state.OfferingState
import com.example.dpop.orchestrator.journey.state.JourneyState
import com.example.dpop.orchestrator.journey.state.StepUpState
import com.example.dpop.orchestrator.journey.state.ToolRef
import com.example.dpop.tool_api.JourneyDebugStep
import com.example.dpop.tool_api.Next
import com.example.dpop.orchestrator.policy.MethodEvidence
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.LiveChannel
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.kc.KeycloakSessionEnded
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import com.example.dpop.orchestrator.kernel.AuthIntent
import com.example.dpop.orchestrator.session.forLog
import com.example.dpop.tool_spi.StepData

/**
 * The machinery between the [IntentStrategy] SPI and the rest of the orchestrator: it turns a
 * [Transition] into the next state, derives `next` from that state, and executes everything a
 * strategy is deliberately not allowed to do itself.
 *
 * The split is the point. A strategy answers "what does this mean" and "where to next" as pure
 * values; account creation, evidence recording, device linking and the ACR cap happen here, once,
 * for every intent alike - so no intent can forget the cap or skip the audit trail.
 *
 * That same split is continued one level down, along the phases every transition passes through,
 * so this class stays the DRIVER rather than also being the doer:
 *
 * | Phase | Who | What |
 * |---|---|---|
 * | read | [JourneyContextFactory] | assembles the read-only [JourneyContext] a strategy decides on |
 * | decide | [IntentStrategy] | turns (state, event, ctx) into a [Transition] - pure values |
 * | act | [JourneyActionExecutor] | executes the [Action] a [Transition.Perform] carries |
 * | route | [JourneyRouting] | derives `next`/[Step] from the resulting state |
 *
 * What remains here is exactly the part none of those four can own alone: journey lifecycle
 * (start/suspend/resume/cancel), the transition loop that sequences them, the attempt budget, and
 * the cancellation fallout.
 */
@Service
@Transactional
class JourneyService(
    private val journeyRepository: AuthJourneyRepository,
    private val codec: JourneyStateCodec,
    strategies: List<IntentStrategy<*>>,
    private val accountService: AccountService,
    private val authContextService: AuthContextService,
    private val sessionManagementService: SessionManagementService,
    private val routing: JourneyRouting,
    private val contextFactory: JourneyContextFactory,
    private val actionExecutor: JourneyActionExecutor,
    private val journeyLogService: JourneyLogService,
    private val journeyLogDetails: JourneyLogDetails,
    private val journeyRecorder: JourneyRecorder,
    // Logout publishes KeycloakSessionEnded rather than calling Keycloak: no listener is
    // registered outside the `keycloak` profile, so the event simply goes nowhere there - which
    // replaces the ObjectProvider<KeycloakAdminClient> this used to hold just to tolerate the
    // bean's absence.
    private val eventPublisher: ApplicationEventPublisher
) {
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
     * [seedAction] is the journey's first transition (docs/04-orchestrierung.md, "RestoreData als
     * erster Übergang"): a mechanical action that runs BEFORE `initialState()`, decided by no
     * strategy - today only `KcChannelService`'s RestoreData case
     * ([Action.ApplyRestoredEvidence]). It is still a real, logged transition (`"Entry"`), just
     * one no [IntentStrategy] ever sees: by the time [IntentStrategy.initialState] runs, its
     * effect (e.g. restored evidence) is already reflected in [JourneyContextFactory.contextFor]'s output, so a
     * strategy that already checks sufficiency on `Started` (like `KcSelectMethodStrategy`) needs
     * no special case for it at all.
     *
     * Only on a [LiveChannel]: an ended channel never gets a journey again (docs/invarianten.md I-1).
     */
    fun start(
        channel: LiveChannel,
        intent: AuthIntent,
        seed: JourneyState? = null,
        seedAction: Action? = null
    ): Step = startJourney(channel.session, intent, seed, parentJourneyId = null, seedAction = seedAction)

    private fun startJourney(
        channel: ChannelSession,
        intent: AuthIntent,
        seed: JourneyState?,
        parentJourneyId: UUID?,
        seedAction: Action?
    ): Step {
        // One running journey per channel (docs/invarianten.md I-3, review 2026-09 M-5). A new
        // top-level journey replaces whatever is still running on the channel - the whole chain,
        // including a parent suspended for a sub-journey - exactly as startLogout always did by
        // hand. A sub-journey (parentJourneyId set) is the one legitimate second journey: its parent
        // was suspended for it on purpose and is not STARTED.
        if (parentJourneyId == null) {
            findActive(checkNotNull(channel.channelSessionId))?.let { cancelChain(it.entity, channel) }
        }
        val journey = AuthJourney(channel.channelSessionId, intent, Instant.now().plus(JOURNEY_TTL))
        journey.accountId = channel.accountId
        journey.parentJourneyId = parentJourneyId
        // A step-up says so on the channel, whether it was asked for directly or demanded as
        // another journey's precondition - the client renders the same screen either way. Only on
        // a channel that IS logged in: STEP_UP_IN_PROGRESS promises that cancelling leads back to
        // AUTHENTICATED, which a cold entry (a peer-login confirmation on a fresh channel) never was.
        if (intent == AuthIntent.STEP_UP && channel.state == ChannelState.AUTHENTICATED) {
            channel.state = ChannelState.STEP_UP_IN_PROGRESS
            sessionManagementService.updateChannelSession(channel)
        }
        val strategy = strategyFor(intent)
        // A placeholder write, purely to satisfy the state columns' NOT NULL constraint before the
        // row can be persisted at all - superseded below once seedAction (if any) has actually run,
        // so no strategy ever observes it.
        codec.write(journey, seed ?: strategy.initialState(contextFactory.contextFor(journey, channel)))
        journeyRepository.save(journey) // journeyId exists from here on, for seedAction's own logging/events.

        seedAction?.let { action ->
            // Anfangs-Übergang der Maschine (Statecharts: Pseudostate -> q0, mit Aktion) -
            // mechanisch, von keiner Strategie entschieden, aber ganz normal über dieselbe
            // Pipeline geloggt wie jeder andere Übergang.
            journeyLogService.record(channel.forLog(), journey.forLog(), "Entry", detail = journeyLogDetails.actionDetail(action, journey, channel))
            actionExecutor.perform(journey, channel, action)
            // Re-derive now that the seed's own effect (e.g. restored evidence) is reflected in ctx
            // - initialState() must never see the placeholder's stale, pre-seed picture.
            codec.write(journey, seed ?: strategy.initialState(contextFactory.contextFor(journey, channel)))
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
    fun startTowardAcr(channel: LiveChannel, targetAcr: AcrLevel, startingAcr: AcrLevel): Step =
        start(channel, AuthIntent.STEP_UP, seed = StepUpState.forSubJourney(targetAcr, startingAcr))

    /**
     * Starts (or restarts) whatever intent this channel was entered with. The intent lives on the
     * channel precisely so neither resume nor cancel has to guess from leftover state what the
     * user was trying to do. [seedAction] is passed straight through to [start] - see that
     * parameter's own doc.
     */
    fun startEntryJourney(channel: LiveChannel, seedAction: Action? = null): Step = startEntryJourney(channel.session, seedAction)

    private fun startEntryJourney(channel: ChannelSession, seedAction: Action?): Step {
        if (channel.state != ChannelState.AUTHENTICATED) {
            channel.state = if (channel.accountId == null) ChannelState.REGISTERING else ChannelState.ANONYMOUS
            sessionManagementService.updateChannelSession(channel)
        }
        return startJourney(channel, channel.entryIntent, seed = null, parentJourneyId = null, seedAction = seedAction)
    }

    /** The one running journey of this channel, if any - a suspended parent is deliberately not it. */
    fun findActive(channelSessionId: UUID): RunningJourney? =
        journeyRepository
            .findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(channelSessionId, JourneyLifecycle.STARTED)
            ?.let(RunningJourney::of)

    /**
     * The journey [journeyId] names, but only while it runs. A tool session outlives its journey's
     * end (logout, cancel, a finished sub-journey) - through this lookup it can no longer reach it.
     */
    fun findRunning(journeyId: UUID): RunningJourney? =
        journeyRepository.findByIdOrNull(journeyId)?.let(RunningJourney::of)

    /**
     * Debug-only view of the running journey chain (docs/tool_api/Envelope.kt, [JourneyDebugStep])
     * - the currently active journey plus every SUSPENDED ancestor it is a sub-journey of, walked
     * via [AuthJourney.parentJourneyId], outermost first. Empty once nothing is running.
     */
    fun debugChain(channel: ChannelSession): List<JourneyDebugStep> {
        val channelSessionId = channel.channelSessionId ?: return emptyList()
        val innermost = findActive(channelSessionId)?.entity ?: return emptyList()
        val chain = mutableListOf(innermost)
        var current = innermost
        while (true) {
            val parent = current.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) } ?: break
            chain.add(parent)
            current = parent
        }
        val availableTools = routing.availableToolsOf(channel)
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
                note = if (it == innermost) innermostNote else null,
                purpose = DemoStepReason.purpose(codec.read(it))
            )
        }
    }

    /**
     * User-initiated abandonment, distinct from an exhausted budget - of the whole chain the user
     * started, not just the sub-journey that happens to be running (see [cancelChain]).
     */
    fun cancel(journey: RunningJourney, channel: LiveChannel) = cancelChain(journey.entity, channel.session)

    private fun cancelJourney(journey: AuthJourney, channel: ChannelSession) {
        markCancelled(journey, channel)
        fallBack(channel)
    }

    /**
     * The one way a channel's login ends - the confirmed logout (`Transition.Logout`), the hard
     * logout (`ChannelService.logout`) and an expired session alike (review 2026-09, M-4: the hard
     * logout used to leave the RefreshToken and the Keycloak session behind).
     *
     * The tokens are discarded, not just unlinked. For an App channel that holds a real Keycloak
     * session (the account-token grant, `AuthContext.keycloakSessionId`) exactly that session is
     * ended - no other session the account holds elsewhere (e.g. a Web-channel login). The Web
     * channel's own logout stays Keycloak's (docs/07-betrieb.md Abschnitt 3). [finalState] is
     * `LOGGED_OUT` or `EXPIRED` - either is terminal.
     */
    fun endSession(channel: LiveChannel, finalState: ChannelState) = endSession(channel.session, finalState)

    private fun endSession(channel: ChannelSession, finalState: ChannelState) {
        require(finalState.isTerminal) { "endSession needs a terminal state, got $finalState" }
        channel.authContextId?.let { authContextService.getAuthContext(it) }?.let { context ->
            if (channel.channel == ChannelType.APP) {
                // Published, not called: this runs inside the caller's transaction, and the Admin
                // API call used to hold it open across a network round trip.
                // KeycloakSessionLogoutListener picks it up AFTER_COMMIT.
                context.keycloakSessionId?.let { eventPublisher.publishEvent(KeycloakSessionEnded(it)) }
            }
            context.refreshToken = null
            context.accessToken = null
            context.refreshExpiresAt = null
            context.accessExpiresAt = null
            authContextService.save(context)
        }
        channel.authContextId = null
        channel.authEvidenceId = null
        channel.state = finalState
        sessionManagementService.updateChannelSession(channel)
    }

    /**
     * Cancels [running] and every ancestor suspended for it - nothing of the chain is left waiting
     * (before, an explicit cancel during a sub-journey left its parent SUSPENDED for good).
     */
    private fun cancelChain(running: AuthJourney, channel: ChannelSession) {
        markCancelled(running, channel)
        var parentId = running.parentJourneyId
        while (parentId != null) {
            val parent = journeyRepository.findByIdOrNull(parentId) ?: break
            if (parent.lifecycle != JourneyLifecycle.SUSPENDED) break
            markCancelled(parent, channel)
            parentId = parent.parentJourneyId
        }
        fallBack(channel)
    }

    private fun markCancelled(journey: AuthJourney, channel: ChannelSession) {
        journey.cancel()
        // Flushed: a parent resumed or a new journey started next must not meet this one still
        // STARTED in the database (ux_journey_running_per_channel).
        journeyRepository.saveAndFlush(journey)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "JOURNEY_CANCELLED", "orchestrator"
        )
        journeyLogService.record(channel.forLog(), journey.forLog(), "CANCELLED", journeyState = codec.read(journey)::class.simpleName)
    }

    // Routing -----------------------------------------------------------------

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). The same
     * [JourneyState.activatable] that answers "may this tool be activated" also decides where the
     * client goes - one function, so the two can never disagree.
     */
    fun nextOf(journey: RunningJourney, channel: ChannelSession): Next = nextOf(journey.entity, channel)

    private fun nextOf(journey: AuthJourney, channel: ChannelSession): Next = routing.nextFor(codec.read(journey), routing.availableToolsOf(channel))

    /** Returns the complete current step, including selection options and prompts. */
    fun stepOf(journey: RunningJourney, channel: ChannelSession): Step =
        routing.stepFor(codec.read(journey.entity), channel)

    // Tool interaction ---------------------------------------------------------

    /**
     * Claims [toolSessionId] as THE current attempt for [tool]. Rejects anything the current
     * state does not offer - which is why LOGIN_LOOKUP cannot be talked into an identification:
     * no state of that intent ever lists one.
     */
    fun activate(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor, toolSessionId: UUID) {
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (tool.toolId !in state.activatable(routing.availableToolsOf(channel))) {
            throw OrchestratorException.invalidState(Text("This tool is not offered in the current step"), "toolId=${tool.toolId}")
        }
        // A concurrent/duplicate activation mints its own ToolSession too; only the one that lands
        // here last becomes current, and the other is correctly rejected by isCurrent afterwards.
        codec.write(journey, state.withActive(ToolRef(tool.toolId, toolSessionId, tool.startStep)))
        journeyRepository.save(journey)
        journeyLogService.record(channel.forLog(), journey.forLog(), "TOOL_ACTIVATED", journeyState = state::class.simpleName, detail = mapOf("toolId" to tool.toolId))
    }

    /** See [JourneyActionExecutor.matchesAttestedIdentity]. */
    fun matchesAttestedIdentity(journey: RunningJourney, channel: ChannelSession, personId: String): Boolean =
        actionExecutor.matchesAttestedIdentity(journey.entity, channel, personId)

    /**
     * `active` survives in the stored state of a consumed/cancelled/failed/suspended journey (nothing
     * clears it on the way out) - that such a journey is never asked is [RunningJourney]'s job, not
     * a lifecycle check here (review 2026-09, S-1).
     */
    fun isCurrent(journey: RunningJourney, toolId: ToolId, toolSessionId: UUID): Boolean =
        codec.read(journey.entity).active?.let { it.toolId == toolId && it.toolSessionId == toolSessionId } ?: false

    fun applyOutcome(
        running: RunningJourney,
        channel: LiveChannel,
        tool: ToolDescriptor,
        outcome: ToolOutcome
    ): Step = applyOutcome(running.entity, channel.session, tool, outcome)

    private fun applyOutcome(
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
            Step(Next.tool(tool.toolId.value, outcome.nextStep, active.toolSessionId), outcome.stepData, outcome.demo)
        }

        is ToolOutcome.Failed -> chargeAttempt(journey, channel, tool, outcome)

        // What this outcome MEANS - and executing that meaning - is entirely the strategy's/
        // machine's business from here: transition() turns it into a Transition.Perform naming
        // the Action, applyTransition executes it (recording the resulting MethodEvidence as part
        // of that, see JourneyActionExecutor) and re-derives the journey with a fresh, post-action context.
        is ToolOutcome.Completed -> advance(journey, channel, JourneyEvent.Completed(tool, outcome))
    }

    /**
     * "Zurück": the running tool ends WITHOUT being declined, and this state's selection page comes
     * back with every candidate still on offer - the abandoned one included, and even when it is the
     * only one: whoever goes back wants to choose again, not to be moved on to the next fallback
     * ([abandon]) or straight back into the same tool. The strategy is not asked - nothing happened
     * that it has to judge. Only an [OfferingState] has a selection page; any other state (a single
     * preferred tool, a skippable assignment) treats going back as [abandon].
     */
    fun back(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor): Step {
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is OfferingState) return abandon(running, live, tool)
        val cleared = state.withOffer(state.offer.withActive(null))
        codec.write(journey, cleared)
        journeyRepository.save(journey)
        journeyLogService.record(channel.forLog(), journey.forLog(), "Back",
            journeyState = state::class.simpleName, detail = mapOf("tool" to tool.toolId.value))
        return routing.selectionFor(cleared, channel)
    }

    /** "Anderes Verfahren": the tool is declined, and the state decides whether anything is left. */
    fun abandon(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor): Step {
        val journey = running.entity
        val channel = live.session
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
    fun answer(running: RunningJourney, live: LiveChannel, answer: String): Step {
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is AnswerableState) {
            throw OrchestratorException.invalidState(Text("Nothing is currently waiting for an answer"))
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
     * `seedAction`, run BEFORE any journey's `initialState()` (docs/04-orchestrierung.md,
     * "RestoreData als erster Übergang") - never through this method, which requires an already-running
     * journey to fire [JourneyEvent.EvidenceReported] against.
     *
     * Called on EVERY evidence-bearing call for [source]: [updates] is the CALLER's complete,
     * currently-valid set for [source] - `AuthEvidence.replaceForSource` drops any existing
     * [source]-owned record whose method is missing here (it expired), so this must run again
     * after every subsequent report, not just once, or a since-expired native method would never
     * actually disappear from the evidence.
     */
    fun applyEvidenceUpdate(running: RunningJourney, live: LiveChannel, source: String, updates: List<MethodEvidence>) {
        val journey = running.entity
        val channel = live.session
        journeyRecorder.mergeEvidence(journey, channel, source, updates)
        advance(journey, channel, JourneyEvent.EvidenceReported)
    }

    // Transitions ----------------------------------------------------------------

    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent): Step {
        val strategy = strategyFor(journey.intent!!)
        val state = codec.read(journey)
        val ctx = contextFactory.contextFor(journey, channel)
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
            val availableTools = routing.availableToolsOf(channel)
            journeyLogService.record(channel.forLog(), journey.forLog(), event::class.simpleName!!,
                journeyState = state::class.simpleName,
                // acrFloor is what this step was actually judged against; resolvedAcr is the
                // account's own CURRENT combined level from ctx.evidence (MFA-bump included, see
                // DefaultAuthPolicy.resolveAcr) - a single Completed entry's own achievedAcr only
                // ever reflects that ONE tool's individual ceiling (e.g. "loa1" for email alone),
                // so without this the log looks like the login never reached loa2 even when the
                // combination of two loa1 factors just did.
                detail = journeyLogDetails.eventDetail(event) +
                    journeyLogDetails.transitionDetail(transition, journey, channel, state, availableTools) { target ->
                        routing.nextFor(target, availableTools)
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
            routing.stepFor(transition.state, channel)
        }

        is Transition.RequireSubJourney -> {
            // The wish stays parked as this journey's state; SUSPENDED plus the child's
            // parentJourneyId is what keeps "one running journey per channel" true.
            codec.write(journey, transition.resumeWith)
            journey.lifecycle = JourneyLifecycle.SUSPENDED
            // Flushed before the child row is written: the database allows one STARTED journey per
            // channel (ux_journey_running_per_channel), and Hibernate would otherwise insert the
            // child before it updates this row.
            journeyRepository.saveAndFlush(journey)
            startJourney(
                channel,
                transition.intent,
                seed = transition.seedWith,
                parentJourneyId = journey.journeyId,
                seedAction = null
            )
        }

        is Transition.Authenticated -> finish(journey, channel)

        is Transition.Perform -> {
            val demoNotice = actionExecutor.perform(journey, channel, transition.action)
            // The one recursive step of the machine: resume at resumeState against a FRESH
            // context (the action just changed evidence/account/whatever it touched) - not the
            // stale one the triggering event arrived with.
            codec.write(journey, transition.resumeState)
            val step = advance(journey, channel, JourneyEvent.ActionCompleted)
            if (demoNotice == null) step else step.copy(demo = step.demo.orEmpty() + demoNotice)
        }

        Transition.Logout -> {
            journey.consume()
            journeyRepository.save(journey)
            journeyLogService.record(channel.forLog(), journey.forLog(), "LOGGED_OUT", journeyState = "LoggedOut")
            // The App channel has no browser/cookie of its own to end - but it may hold a real
            // Keycloak session from the custom account-token grant
            // (AccountTokenGrantType's reused session, AuthContext.keycloakSessionId). Ending only
            // THAT one session here keeps "logout means logout" true for this channel, without
            // touching any other session the same account happens to also be logged into
            // elsewhere (e.g. a separate Web-channel browser login). The Web channel's own logout
            // stays entirely Keycloak's (docs/07-betrieb.md Abschnitt 3) - never reaches this
            // transition for that channel.
            endSession(channel, ChannelState.LOGGED_OUT)
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
                // outcome as an explicit DELETE .../journey - unless fallBack
                // already landed the channel back on AUTHENTICATED, in which case there is
                // nothing to restart (same guard as ChannelService.cancelActiveJourney).
                cancelJourney(journey, channel)
                if (channel.state == ChannelState.AUTHENTICATED) Step(Next.AUTHENTICATED) else startEntryJourney(channel, seedAction = null)
            }
        }

        is Transition.Abort -> {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted(transition.reason)
        }
    }

    /** Folds a [JourneyActionExecutor.perform] demo notice into the already-computed next step's own `demo` block, if any. */

    private fun finish(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume()
        // Flushed before a suspended parent resumes (ux_journey_running_per_channel).
        journeyRepository.saveAndFlush(journey)

        val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
        if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
            // The parent picks up exactly where it was parked, so the original wish survives.
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyFinished(journey.intent!!, contextFactory.currentAcrOf(channel)))
        }

        channel.state = ChannelState.AUTHENTICATED
        sessionManagementService.updateChannelSession(channel)
        return Step(Next.AUTHENTICATED)
    }


    /**
     * The attempt budget spans the whole journey (docs/04-orchestrierung.md #7). A failed attempt
     * with budget left is not an HTTP error - it behaves like missing input; only an exhausted
     * budget ends things terminally, and it ends the JOURNEY, not just the current state.
     */
    private fun chargeAttempt(journey: AuthJourney, channel: ChannelSession, tool: ToolDescriptor, outcome: ToolOutcome.Failed): Step {
        journey.attemptBudget -= 1
        journeyLogService.record(channel.forLog(), journey.forLog(), "TOOL_FAILED",
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
            throw OrchestratorException.processAborted(Text("Retry-Limit erreicht: {reason}", "reason" to outcome.reason))
        }
        journeyRepository.save(journey)
        return Step(nextOf(journey, channel), FailedAttemptStep(outcome.reason))
    }

    // Cancellation fallout -------------------------------------------------------

    /**
     * Where the channel lands after an abandoned journey: back to the login status it had before -
     * one rule for every intent ([ChannelState.isLoggedIn]). Each strategy used to name its own
     * fallback, and six of them said AUTHENTICATED on the assumption that they only ever run on a
     * logged-in channel. A cold peer-login confirmation and a step-up before the login broke that
     * assumption: cancelling them claimed AUTHENTICATED without any proof, which only the database
     * refused (docs/invarianten.md I-4).
     *
     * Account and evidence are re-derived from the DURABLE truth rather than blindly kept or
     * blindly wiped: the device link is what survives a journey, an AuthContext is not.
     */
    private fun fallBack(channel: ChannelSession) {
        val target = if (channel.state?.isLoggedIn == true) ChannelState.AUTHENTICATED else ChannelState.ANONYMOUS
        channel.state = target
        if (target != ChannelState.AUTHENTICATED) {
            channel.authContextId = null
            channel.authEvidenceId = null
            val abandonedAccountId = channel.accountId
            channel.accountId = if (channel.entryIntent.startsFromDeviceLink && channel.bindingKeyRef != null) {
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
     * flow ever leaves a provisional account behind.
     *
     * "Nothing durable attached" is [AccountProfile.isProvisional], the same named rule
     * `AccountService.absorbProvisionalAccount` (ADR-20) is allowed to let an account yield by -
     * one rule, two consequences, rather than two hand-written conditions free to drift apart.
     */
    private fun deleteIfAbandonedUnidentified(accountId: Long?) {
        if (accountId == null) return
        // The account module decides - it deletes only a still-provisional account.
        accountService.deleteProvisionalAccount(accountId)
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

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
