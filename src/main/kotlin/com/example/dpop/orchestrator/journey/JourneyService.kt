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
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The machinery between the [IntentStrategy] SPI and the rest of the orchestrator: it turns a
 * [Decision] into the next state, derives `next` from that state, and executes everything a
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
    private val sessionManagementService: SessionManagementService,
    private val toolRegistry: ToolHandlerRegistry,
    private val authPolicy: AuthPolicy,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val accountDeletionService: AccountDeletionService,
    private val journeyLogService: JourneyLogService
) {
    /**
     * `next` plus whatever the step needs to render - the pair every caller wants back. `next` is
     * null only for a decision that ends the channel for good ([Decision.DeleteAccount]) -
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
     */
    fun start(
        channel: ChannelSession,
        intent: AuthIntent,
        seed: JourneyState? = null,
        parentJourneyId: UUID? = null
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
        codec.write(journey, seed ?: strategy.initialState(contextFor(journey, channel)))
        journeyRepository.save(journey)
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
     * user was trying to do.
     */
    fun startEntryJourney(channel: ChannelSession): Step {
        if (channel.state != ChannelState.AUTHENTICATED) {
            channel.state = if (channel.accountId == null) ChannelState.REGISTERING else ChannelState.ANONYMOUS
            sessionManagementService.updateChannelSession(channel)
        }
        return start(channel, channel.entryIntent)
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
        journeyLogService.record(channel, journey, "CANCELLED")
    }

    // Routing -----------------------------------------------------------------

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). The same
     * [JourneyState.activatable] that answers "may this tool be activated" also decides where the
     * client goes - one function, so the two can never disagree.
     */
    fun nextOf(journey: AuthJourney, channel: ChannelSession): Next = nextFor(codec.read(journey), availableToolsOf(channel))

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
        journeyLogService.record(channel, journey, "TOOL_ACTIVATED", mapOf("toolId" to tool.toolId))
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

        is ToolOutcome.Completed -> {
            val effectiveAcr = applyEffect(journey, channel, tool, outcome)
            val authContextId = checkNotNull(channel.authContextId) { "AuthContext missing after ${tool.toolId}" }
            authContextService.applyEvidence(authContextId, outcome.amr, outcome.factorTypes, effectiveAcr)
            sessionManagementService.recordEvent(
                channel.channelSessionId, journey.journeyId, "TOOL_COMPLETED:${tool.toolId}", "orchestrator"
            )
            advance(journey, channel, JourneyEvent.Completed(tool, outcome))
        }
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
     * `next` decides for them can all change without this method ever changing.
     */
    fun answer(journey: AuthJourney, channel: ChannelSession, answer: String): Step {
        val state = codec.read(journey)
        if (state !is AnswerableState) {
            throw OrchestratorException.invalidState("Nothing is currently waiting for an answer")
        }
        return advance(journey, channel, JourneyEvent.Answered(answer))
    }

    // Decisions ----------------------------------------------------------------

    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent): Step {
        val strategy = strategyFor(journey.intent!!)
        val state = codec.read(journey)
        val decision = strategy.decideErased(state, event, contextFor(journey, channel))
        journeyLogService.record(
            channel, journey, event::class.simpleName!!,
            eventDetail(event) + decisionDetail(decision, journey, channel)
        )
        return applyDecision(journey, channel, decision)
    }

    /** The extra, event-specific detail worth keeping in the JourneyLog - which tool was involved, and how the outcome/answer read. */
    private fun eventDetail(event: JourneyEvent): Map<String, Any?> = when (event) {
        is JourneyEvent.Completed -> mapOf("toolId" to event.tool.toolId, "method" to event.tool.method) + outcomeDetail(event.outcome)
        is JourneyEvent.Abandoned -> mapOf("toolId" to event.tool.toolId)
        is JourneyEvent.Answered -> mapOf("answer" to event.answer)
        is JourneyEvent.SubJourneyFinished -> mapOf("subIntent" to event.intent.name, "achievedAcr" to event.achievedAcr)
        is JourneyEvent.SubJourneyCancelled -> mapOf("subIntent" to event.intent.name)
        JourneyEvent.Started -> emptyMap()
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
        }
        return common + specific
    }

    /**
     * Where the decision leads - the concrete follow-up (target state/sub-intent/effect), not just
     * which Decision variant fired. For [Decision.Advance], resolves the actual next tool(s) via
     * [toolRegistry] the same way [nextFor] does, so the log shows what the client will see, not
     * just the internal state-class name.
     */
    private fun decisionDetail(decision: Decision, journey: AuthJourney, channel: ChannelSession): Map<String, Any?> = when (decision) {
        is Decision.Advance -> {
            val availableTools = availableToolsOf(channel)
            val candidates = decision.to.activatable(availableTools)
            val next = nextFor(decision.to, availableTools)
            mapOf(
                "decision" to "Advance",
                "toState" to decision.to::class.simpleName,
                "candidateTools" to candidates.map { toolId -> toolId to toolRegistry.descriptorOf(toolId).method }.toMap(),
                "next" to mapOf("type" to next.type, "toolId" to next.toolId, "context" to next.context, "step" to next.step)
            )
        }
        is Decision.RequireSubJourney -> mapOf(
            "decision" to "RequireSubJourney", "subIntent" to decision.intent.name, "targetAcr" to decision.targetAcr
        )
        Decision.Finish -> mapOf("decision" to "Finish")
        is Decision.Execute -> mapOf("decision" to "Execute", "effect" to decision.effect::class.simpleName) + effectDetail(decision.effect, journey, channel)
        is Decision.DeleteAccount -> mapOf("decision" to "DeleteAccount", "accountId" to decision.accountId)
        Decision.Cancel -> mapOf("decision" to "Cancel")
        is Decision.Abort -> mapOf("decision" to "Abort", "reason" to decision.reason)
    }

    /**
     * Which method/account a [Decision.Execute]'s effect actually names - the effect's class name
     * alone (e.g. "Remove") doesn't say which method was removed. Resolves [Effect.Remove]'s
     * method/label the same way [removeMethod] itself does, purely for a readable log entry - a
     * second, disposable lookup, not the one that actually authorizes/executes the removal.
     */
    private fun effectDetail(effect: Effect, journey: AuthJourney, channel: ChannelSession): Map<String, Any?> = when (effect) {
        is Effect.Remove -> {
            val accountId = journey.accountId ?: channel.accountId
            val target = accountId?.let { accountService.findAccount(it) }
                ?.authenticationMethods?.firstOrNull { it.id == effect.methodInstanceId }
            mapOf("methodInstanceId" to effect.methodInstanceId, "method" to target?.method, "label" to target?.label)
        }
        is Effect.LinkDevice -> mapOf("accountId" to effect.accountId)
        is Effect.AdoptIdentity, is Effect.ConfirmIdentity, is Effect.AdoptCredential, is Effect.AcceptProof -> emptyMap()
    }

    private fun applyDecision(journey: AuthJourney, channel: ChannelSession, decision: Decision): Step = when (decision) {
        is Decision.Advance -> {
            codec.write(journey, decision.to)
            journeyRepository.save(journey)
            stepFor(decision.to, availableToolsOf(channel))
        }

        is Decision.RequireSubJourney -> {
            // The wish stays parked as this journey's state; SUSPENDED plus the child's
            // parentJourneyId is what keeps "one running journey per channel" true.
            codec.write(journey, decision.resumeWith)
            journey.lifecycle = JourneyLifecycle.SUSPENDED
            journeyRepository.save(journey)
            start(
                channel,
                decision.intent,
                seed = seedFor(decision, channel),
                parentJourneyId = journey.journeyId
            )
        }

        is Decision.Finish -> finish(journey, channel)

        is Decision.Execute -> {
            performEffect(journey, channel, decision.effect)
            applyDecision(journey, channel, decision.then)
        }

        is Decision.DeleteAccount -> {
            // Independent re-check against a freshly derived ctx, not the decision's own account
            // (see Decision.DeleteAccount's doc) or DeleteAccountStrategy directly.
            val freshCtx = contextFor(journey, channel)
            val account = checkNotNull(freshCtx.account) { "DeleteAccount without a resolved account" }
            check(authPolicy.isSatisfied(freshCtx.evidence, Decision.DeleteAccount.REQUIRED_ACR, account)) {
                "${journey.intent} decided Decision.DeleteAccount without satisfying ${Decision.DeleteAccount.REQUIRED_ACR}"
            }
            journey.consume()
            journeyRepository.save(journey)
            // Via freshly queried entities, not the cascade's own writes - `channel` here is what
            // buildChannelBlock reads for the response, and must show the post-deletion state.
            accountDeletionService.deleteAccount(decision.accountId)
            channel.state = ChannelState.LOGGED_OUT
            channel.authContextId = null
            Step(next = null)
        }

        is Decision.Cancel -> {
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

        is Decision.Abort -> {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted(decision.reason)
        }
    }

    /** The two [Effect]s a [Decision.Execute] can carry - see that type's own doc for why the other four (tool-outcome-only) are unreachable here. */
    private fun performEffect(journey: AuthJourney, channel: ChannelSession, effect: Effect) {
        when (effect) {
            is Effect.Remove -> removeMethod(journey, channel, effect.methodInstanceId)
            is Effect.LinkDevice -> sessionManagementService.linkDeviceToAccount(
                checkNotNull(channel.bindingKeyRef) { "LinkDevice without a bindingKeyRef" },
                effect.accountId
            )
            is Effect.AdoptIdentity, is Effect.ConfirmIdentity, is Effect.AdoptCredential, is Effect.AcceptProof ->
                error("$effect is only ever produced by IntentStrategy.interpret(), never wrapped in Decision.Execute")
        }
    }

    private fun seedFor(decision: Decision.RequireSubJourney, channel: ChannelSession): JourneyState =
        strategyFor(decision.intent).initialStateForSubJourneyAcr(decision.targetAcr, currentAcrOf(channel))

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
     * The one effect a strategy may ask for that is not a tool run. Guarded against self-lockout:
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
            mapOf(
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

    // Effect execution --------------------------------------------------

    /**
     * Executes what the strategy decided the outcome MEANS and returns this run's effective ACR.
     * The cap `min(achievedAcr, enrolledUnderAcr)` lives here and only here: a method must never
     * authenticate to more trust than it was set up under, and no intent may opt out of that.
     */
    private fun applyEffect(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed
    ): String? {
        val strategy = strategyFor(journey.intent!!)
        return when (val effect = strategy.interpretErased(codec.read(journey), tool, outcome)) {
            is Effect.AdoptIdentity -> {
                val identified = outcome as ToolOutcome.Completed.Identified
                val account = accountService.findOrCreateAccount(identified.personId)
                bindAccount(journey, channel, account.accountId)
                recordIdentification(journey, channel, tool, identified)
                identified.achievedAcr
            }

            is Effect.ConfirmIdentity -> {
                val identified = outcome as ToolOutcome.Completed.Identified
                val accountId = checkNotNull(journey.accountId ?: channel.accountId) {
                    "Identified without a known account under ${journey.intent}"
                }
                val account = checkNotNull(accountService.findAccount(accountId)) { "Account not found: $accountId" }
                if (account.personId != identified.personId) {
                    throw OrchestratorException.invalidState("Identifizierte Person passt nicht zum angemeldeten Konto")
                }
                bindAccount(journey, channel, accountId)
                recordIdentification(journey, channel, tool, identified)
                identified.achievedAcr
            }

            is Effect.AdoptCredential -> {
                val enrolled = outcome as ToolOutcome.Completed.Enrolled
                val accountId = checkNotNull(journey.accountId ?: channel.accountId) { "Enrolled without an account" }
                val authContextId = checkNotNull(channel.authContextId) { "Enrolled without an AuthContext" }
                val authContext = checkNotNull(authContextService.getAuthContext(authContextId)) {
                    "AuthContext not found: $authContextId"
                }
                // `label` is lifted into its own field rather than staying in the generic details
                // blob, so the API can surface it without clients reaching into details.
                val label = enrolled.auditDetails?.get("label") as? String
                accountService.addAuthenticationMethod(
                    accountId,
                    tool.method,
                    enrolled.enrollmentRef,
                    enrolledUnderAcr = authContext.currentAcr,
                    details = enrolled.auditDetails.orEmpty().minus("label") + mapOf(
                        "enrolledUnderAmr" to authContext.currentAmr,
                        "channel" to channel.channel?.name
                    ),
                    allowsMultipleInstances = tool.allowsMultipleInstances,
                    label = label
                )
                if (effect.bindDevice) {
                    sessionManagementService.linkDeviceToAccount(channel.bindingKeyRef!!, accountId)
                }
                enrolled.achievedAcr
            }

            is Effect.AcceptProof -> {
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                val resolved = authenticated.accountId.takeIf { effect.useOutcomeAccount }
                val accountId = checkNotNull(resolved ?: journey.accountId ?: channel.accountId) {
                    "Authenticated without a known account"
                }
                bindAccount(journey, channel, accountId)
                if (effect.bindDevice) {
                    sessionManagementService.linkDeviceToAccount(channel.bindingKeyRef!!, accountId)
                }
                val used = checkNotNull(accountService.findActiveMethod(accountId, tool.method)) {
                    "No active method '${tool.method}' for account $accountId"
                }
                AcrLevels.min(authenticated.achievedAcr, used.enrolledUnderAcr)
            }

            is Effect.Remove, is Effect.LinkDevice ->
                error("$effect is only ever produced as part of a Decision.Execute, never returned from interpret()")
        }
    }

    private fun bindAccount(journey: AuthJourney, channel: ChannelSession, accountId: Long) {
        journey.accountId = accountId
        channel.accountId = accountId
        if (channel.authContextId == null) {
            // Fresh login: start a new evidence trail rather than reuse a stale one.
            channel.authContextId = authContextService.createForAccount(accountId).authContextId
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
        val authContext = channel.authContextId?.let { authContextService.getAuthContext(it) }
        return JourneyContext(
            account = accountId?.let { accountService.findAccount(it) },
            evidence = AuthEvidence(
                authContext?.currentAmr ?: emptyList(),
                authContext?.currentFactorTypes ?: emptySet()
            ),
            acrFloor = acrFloorOf(channel),
            bindingKeyRef = checkNotNull(channel.bindingKeyRef) { "Channel without a binding key" },
            linkedAccountId = channel.bindingKeyRef?.let { sessionManagementService.findLinkedAccountId(it) },
            isSubJourney = journey.parentJourneyId != null,
            policy = authPolicy,
            catalog = toolRegistry,
            availableTools = availableToolsOf(channel)
        )
    }

    private fun acrFloorOf(channel: ChannelSession): String = channel.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR

    private fun currentAcrOf(channel: ChannelSession): String =
        channel.authContextId?.let { authContextService.getAuthContext(it)?.currentAcr } ?: "none"

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
    private fun IntentStrategy<*>.decideErased(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Decision =
        (this as IntentStrategy<JourneyState>).decide(state, event, ctx)

    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.interpretErased(
        state: JourneyState,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed
    ): Effect = (this as IntentStrategy<JourneyState>).interpret(state, tool, outcome)

    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.cancelledToErased(state: JourneyState): ChannelState =
        (this as IntentStrategy<JourneyState>).cancelledTo(state)

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
