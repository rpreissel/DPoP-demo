package com.example.dpop.orchestrator.journey

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.tool_api.Next
import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.orchestrator.policy.AuthPolicy
import com.example.dpop.orchestrator.session.AcrLevels
import com.example.dpop.orchestrator.session.AuthContextService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.SessionManagementService
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
    private val authPolicy: AuthPolicy
) {
    /** `next` plus whatever the step needs to render - the pair every caller wants back. */
    data class Step(val next: Next, val stepData: Map<String, Any?>? = null)

    private val strategiesByIntent: Map<AuthIntent, IntentStrategy<*>> = strategies.associateBy { it.intent }

    init {
        val missing = AuthIntent.entries.filterNot { it in strategiesByIntent }
        check(missing.isEmpty()) { "No IntentStrategy registered for: $missing" }
    }

    // Lifecycle ---------------------------------------------------------------

    /**
     * Starts a journey and immediately produces its first offer. [seed] lets a caller name the
     * concrete wish the journey exists for (a step-up target, a method to remove) - without it
     * the strategy's own [IntentStrategy.initial] applies.
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
        codec.write(journey, seed ?: strategy.initial(contextFor(journey, channel)))
        journeyRepository.save(journey)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "JOURNEY_STARTED:$intent", "orchestrator"
        )
        return advance(journey, channel, JourneyEvent.Started)
    }

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

    fun stateOf(journey: AuthJourney): JourneyState = codec.read(journey)

    /** User-initiated abandonment of the whole journey, distinct from an exhausted budget. */
    fun cancel(journey: AuthJourney, channel: ChannelSession) {
        journey.cancel()
        journeyRepository.save(journey)
        sessionManagementService.recordEvent(
            channel.channelSessionId, journey.journeyId, "JOURNEY_CANCELLED", "orchestrator"
        )
        fallBack(journey, channel)
    }

    // Routing -----------------------------------------------------------------

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). The same
     * [JourneyState.activatable] that answers "may this tool be activated" also decides where the
     * client goes - one function, so the two can never disagree.
     */
    fun nextOf(journey: AuthJourney): Next = nextFor(codec.read(journey))

    private fun nextFor(state: JourneyState): Next {
        state.active?.let { return Next.tool(it.toolId, it.step, it.toolSessionId) }
        val activatable = state.activatable()
        return if (activatable.size == 1) {
            val toolId = activatable.single()
            Next.tool(toolId, toolRegistry.descriptorOf(toolId).startStep)
        } else {
            // Several candidates open a selection page; none means an orchestrator-owned page
            // that is not a choice at all (a confirmation, the finished screen).
            Next.orchestrator(state.selectionContext, state.selectionStep)
        }
    }

    private fun stepFor(state: JourneyState): Step {
        val options = state.activatable()
        return Step(nextFor(state), if (options.size > 1) mapOf("options" to options.toList()) else null)
    }

    // Tool interaction ---------------------------------------------------------

    /**
     * Claims [toolSessionId] as THE current attempt for [tool]. Rejects anything the current
     * state does not offer - which is why LOGIN_LOOKUP cannot be talked into an identification:
     * no state of that intent ever lists one.
     */
    fun activate(journey: AuthJourney, tool: ToolDescriptor, toolSessionId: UUID) {
        val state = codec.read(journey)
        if (tool.toolId !in state.activatable()) {
            throw OrchestratorException.invalidState("${tool.toolId} is not offered in the current step")
        }
        // A concurrent/duplicate activation mints its own ToolSession too; only the one that lands
        // here last becomes current, and the other is correctly rejected by isCurrent afterwards.
        codec.write(journey, state.withActive(ToolRef(tool.toolId, toolSessionId, tool.startStep)))
        journeyRepository.save(journey)
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

        is ToolOutcome.Failed -> chargeAttempt(journey, outcome.reason)

        is ToolOutcome.Completed -> {
            val effectiveAcr = applyInterpretation(journey, channel, tool, outcome)
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
     * The answer to [LookupState.OfferBinding]. Agreeing is the ONLY way a lookup login ever
     * produces a device link - it must never arise as a side effect of the login itself.
     */
    fun answerBinding(journey: AuthJourney, channel: ChannelSession, accept: Boolean): Step {
        val state = codec.read(journey)
        if (state !is LookupState.OfferBinding) {
            throw OrchestratorException.invalidState("No device binding is currently being offered")
        }
        if (accept) {
            sessionManagementService.linkDeviceToAccount(channel.bindingKeyRef!!, state.accountId)
        }
        return advance(journey, channel, JourneyEvent.SubJourneyFinished(null))
    }

    // Decisions ----------------------------------------------------------------

    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent): Step {
        val strategy = strategyFor(journey.intent!!)
        val state = codec.read(journey)

        return when (val decision = strategy.decide(state, event, contextFor(journey, channel))) {
            is Decision.Advance -> {
                codec.write(journey, decision.to)
                journeyRepository.save(journey)
                stepFor(decision.to)
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

            is Decision.Remove -> {
                removeMethod(journey, channel, decision.methodInstanceId)
                finish(journey, channel)
            }

            // Giving up on the last thing this journey could offer is the same outcome as an
            // explicit DELETE .../process: the channel starts its own entry intent afresh.
            is Decision.Cancel -> {
                cancel(journey, channel)
                startEntryJourney(channel)
            }

            is Decision.Abort -> {
                journey.fail()
                journeyRepository.save(journey)
                throw OrchestratorException.processAborted(decision.reason)
            }
        }
    }

    private fun seedFor(decision: Decision.RequireSubJourney, channel: ChannelSession): JourneyState? =
        if (decision.intent == AuthIntent.STEP_UP) {
            StepUpState.Start(decision.targetAcr, startingAcr = currentAcrOf(channel))
        } else {
            null
        }

    private fun finish(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume()
        journeyRepository.save(journey)

        val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
        if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
            // The parent picks up exactly where it was parked, so the original wish survives.
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyFinished(currentAcrOf(channel)))
        }

        channel.state = ChannelState.AUTHENTICATED
        sessionManagementService.updateChannelSession(channel)
        return Step(Next.orchestrator("authentication", "authenticated"))
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
    private fun chargeAttempt(journey: AuthJourney, reason: String): Step {
        journey.attemptBudget -= 1
        if (journey.attemptBudget <= 0) {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted("Retry-Limit erreicht: $reason")
        }
        journeyRepository.save(journey)
        return Step(nextOf(journey), mapOf("error" to reason))
    }

    // Interpretation execution --------------------------------------------------

    /**
     * Executes what the strategy decided the outcome MEANS and returns this run's effective ACR.
     * The cap `min(achievedAcr, enrolledUnderAcr)` lives here and only here: a method must never
     * authenticate to more trust than it was set up under, and no intent may opt out of that.
     */
    private fun applyInterpretation(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed
    ): String? {
        val strategy = strategyFor(journey.intent!!)
        return when (val interpretation = strategy.interpretOutcome(codec.read(journey), tool, outcome)) {
            is Interpretation.AdoptIdentity -> {
                val identified = outcome as ToolOutcome.Completed.Identified
                val account = accountService.findOrCreateAccount(identified.personId)
                bindAccount(journey, channel, account.accountId)
                recordIdentification(journey, channel, tool, identified)
                identified.achievedAcr
            }

            is Interpretation.ConfirmIdentity -> {
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

            is Interpretation.AdoptCredential -> {
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
                if (interpretation.bindDevice) {
                    sessionManagementService.linkDeviceToAccount(channel.bindingKeyRef!!, accountId)
                }
                enrolled.achievedAcr
            }

            is Interpretation.AcceptProof -> {
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                val resolved = authenticated.accountId.takeIf { interpretation.useOutcomeAccount }
                val accountId = checkNotNull(resolved ?: journey.accountId ?: channel.accountId) {
                    "Authenticated without a known account"
                }
                bindAccount(journey, channel, accountId)
                if (interpretation.bindDevice) {
                    sessionManagementService.linkDeviceToAccount(channel.bindingKeyRef!!, accountId)
                }
                val used = checkNotNull(accountService.findActiveMethod(accountId, tool.method)) {
                    "No active method '${tool.method}' for account $accountId"
                }
                AcrLevels.min(authenticated.achievedAcr, used.enrolledUnderAcr)
            }
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
        val target = strategy.cancelledTo(codec.read(journey))
        channel.state = target
        if (target != ChannelState.AUTHENTICATED) {
            channel.authContextId = null
            channel.accountId = if (channel.entryIntent == AuthIntent.FAST) {
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
            catalog = toolRegistry
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
     * instead of in every strategy.
     */
    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.decide(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Decision =
        (this as IntentStrategy<JourneyState>).next(state, event, ctx)

    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.interpretOutcome(
        state: JourneyState,
        tool: ToolDescriptor,
        outcome: ToolOutcome.Completed
    ): Interpretation = (this as IntentStrategy<JourneyState>).interpret(state, tool, outcome)

    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.cancelledTo(state: JourneyState): ChannelState =
        (this as IntentStrategy<JourneyState>).onCancel(state)

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
