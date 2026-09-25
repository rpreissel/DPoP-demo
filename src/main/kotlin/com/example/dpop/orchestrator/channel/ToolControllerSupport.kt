package com.example.dpop.orchestrator.channel

import com.example.dpop.orchestrator.session.ToolSessionStatus
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.journey.RunningJourney
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.journey.Step
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.orchestrator.policy.requiresSatisfied
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.LiveChannel
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.IdentThrottleService
import com.example.dpop.orchestrator.session.LoginThrottleService
import com.example.dpop.orchestrator.session.SendThrottleService
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.texts.Text
import com.example.dpop.tool_api.API_V1
import com.example.dpop.tool_api.AuthorizedToolContext
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DemoInfo
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_api.ToolContext
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId
import com.example.dpop.tool_spi.ToolOutcome
import java.net.URI
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder

/**
 * Plumbing shared by every tool-specific controller: binding check, throttling, tool-session
 * lifecycle, response envelope. Each controller stays tool-specific
 * (docs/08-projektrahmen.md A11); only this cross-cutting part is centralized so it can't
 * silently diverge between tools.
 *
 * Implements [ToolEndpoint] - the SPI a tool controller is written against once it moves into its
 * own method module. [Context] implements [ToolContext] but is never exposed as
 * more than that interface to any caller (not even [ToolSwitchController] next to it, which only
 * ever sees [ToolContext] too - see [abandon]); the methods whose interface
 * signature takes `context: ToolContext` cast it back to [Context] internally, safe because this
 * class is the only place a [Context] is ever constructed.
 *
 * What is deliberately NOT here: any knowledge of which tool may run when. That is a
 * question about the journey's current state, and [JourneyService] is the only thing that answers
 * it.
 */
@Service
@Transactional
class ToolControllerSupport(
    private val sessionManagementService: SessionManagementService,
    private val channelAccessGuard: ChannelAccessGuard,
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService,
    private val loginThrottleService: LoginThrottleService,
    private val identThrottleService: IdentThrottleService,
    private val sendThrottleService: SendThrottleService,
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val demoDisclosure: DemoDisclosure
) : ToolEndpoint {
    data class Context(
        override val toolId: String,
        override val toolSessionId: UUID,
        val journeyId: UUID,
        val channelSessionId: UUID,
        val bindingKeyRef: String,
        override val journeyAccountId: Long?,
        override val channelAccountId: Long?
    ) : AuthorizedToolContext

    override fun activationLocation(context: ToolContext, baseUri: URI): URI =
        UriComponentsBuilder.fromUri(baseUri)
            .replacePath("$API_V1/tools/{toolSessionId}/{toolId}")
            .buildAndExpand(context.toolSessionId, context.toolId)
            .toUri()

    /**
     * Mints the ToolSession and lets the journey decide whether [toolId] may run at all. The
     * check is membership in the current state's own offer, so a tool that was never offered
     * cannot be activated by naming it.
     */
    override fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String): Context {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val channel = live.session
        val journey = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey for this channel"))
        val descriptor = toolRegistry.descriptorOf(ToolId(toolId))

        validatePreconditions(toolId, channel)
        // Only reachable for a tool whose account the CHANNEL already knows - i.e. a IDENTIFIED_AUTH
        // tool. A LOOKUP_AUTH tool has no accountId here by definition (it resolves one from
        // submitted input later), and an IDENT tool has none at all; those two are throttled at
        // the point they resolve their own subject, via isLockedOut/isIdentLockedOut, and answer
        // with their ordinary failure instead of this explicit 423.
        if (descriptor.role.category == ToolCategory.AUTH) {
            channel.accountId?.let { loginThrottleService.assertNotLocked(it) }
        }

        val toolSession = sessionManagementService.createToolSession(journey.journeyId, TOOL_TTL)
        journeyService.activate(journey, live, descriptor, toolSession.toolSessionId!!)
        return Context(
            toolId = toolId,
            toolSessionId = checkNotNull(toolSession.toolSessionId),
            journeyId = journey.journeyId,
            channelSessionId = checkNotNull(channel.channelSessionId),
            bindingKeyRef = bindingKeyRef,
            journeyAccountId = journey.accountId,
            channelAccountId = channel.accountId
        )
    }

    /**
     * [JourneyService.activate] only checks that the current state offers the tool. It does not,
     * and structurally cannot, know about preconditions a tool carries for itself. Without this,
     * a client could activate a gated tool (enroll-password, which needs a confirmed email)
     * directly, bypassing the fact that the candidate list silently excluded it.
     */
    private fun validatePreconditions(toolId: String, channel: ChannelSession) {
        // Same reasoning as the requires gate below: JourneyService.activate only checks
        // membership in the current state's offer, which already excludes unavailable tools - but a
        // direct activation call must be re-checked here, defensively, against both availability
        // axes (docs/03-tool-architektur.md, availability).
        if (toolId !in channel.availableClientTools || !toolAvailabilityService.isEnabled(toolId, checkNotNull(channel.channel))) {
            throw OrchestratorException.invalidState(Text("This tool is not available on this channel"), "toolId=${toolId}")
        }

        val descriptor = toolRegistry.descriptorOf(ToolId(toolId))
        val account = channel.accountId?.let { accountService.findAccount(it) }
        descriptor.requires.forEach { requirement ->
            if (!requiresSatisfied(requirement, account)) {
                throw OrchestratorException.invalidState(
                    Text("This tool requires a prior proof first"), "toolId=${toolId}, requirement=${requirement.attributeType.wireName}, minTrustLevel=${requirement.minTrustLevel}"
                )
            }
        }
    }

    /**
     * The write path: same load as [loadContext], plus the authorization that [applyOutcome]'s
     * parameter type demands. Callers cannot get an [AuthorizedToolContext] for an existing
     * session any other way, so the check is structural rather than remembered.
     */
    override fun loadCurrent(toolSessionId: UUID, bindingKeyRef: String, toolId: String): Context {
        val context = loadContext(toolSessionId, bindingKeyRef, toolId)
        requireCurrentTool(context)
        return context
    }

    override fun loadContext(toolSessionId: UUID, bindingKeyRef: String, toolId: String): Context {
        val toolSession = sessionManagementService.findToolSessionById(toolSessionId)
            ?: throw OrchestratorException.notFound(Text("Tool session not found"), "toolSessionId=${toolSessionId}")
        val journey = journeyService.findRunning(toolSession.journeyId!!)
            ?: throw OrchestratorException.processGone(Text("Journey for this tool session is gone"))
        val channel = channelAccessGuard.requireChannel(journey.channelSessionId, bindingKeyRef)
        return Context(
            toolId = toolId,
            toolSessionId = toolSessionId,
            journeyId = journey.journeyId,
            channelSessionId = checkNotNull(channel.channelSessionId),
            bindingKeyRef = bindingKeyRef,
            journeyAccountId = journey.accountId,
            channelAccountId = channel.accountId
        )
    }

    private fun requireCurrentTool(context: ToolContext) {
        if (!isCurrentTool(context)) {
            throw OrchestratorException.invalidState(Text("This tool is not the currently active step of this journey"), "toolId=${context.toolId}")
        }
    }

    override fun isCurrentTool(context: ToolContext): Boolean {
        val ctx = context as Context
        val journey = resolveJourney(ctx)
        return journeyService.isCurrent(journey, ToolId(ctx.toolId), ctx.toolSessionId)
    }

    /**
     * "Back"/"Switch". Invalidates the ToolSession immediately rather than waiting for its TTL: a
     * re-offered candidate can be the same toolId as the one being abandoned, so toolId matching
     * alone can't tell the abandoned session and a freshly re-activated one apart.
     */
    override fun abandon(context: AuthorizedToolContext): ChannelResponse =
        leave(context) { journey, channel, tool -> journeyService.abandon(journey, channel, tool) }

    /** "Zurück" - same session handling as [abandon], only the journey is not told the tool was declined. */
    override fun back(context: AuthorizedToolContext): ChannelResponse =
        leave(context) { journey, channel, tool -> journeyService.back(journey, channel, tool) }

    private fun leave(context: AuthorizedToolContext, move: (RunningJourney, LiveChannel, ToolDescriptor) -> Step): ChannelResponse {
        val ctx = context as Context
        val journey = resolveJourney(ctx)
        val live = resolveChannel(ctx, journey)
        val channel = live.session
        sessionManagementService.endToolSession(ctx.toolSessionId, ToolSessionStatus.ABANDONED)
        val step = move(journey, live, toolRegistry.descriptorOf(ToolId(ctx.toolId)))
        return ChannelResponse(
            channel = channelService.buildChannelBlock(channel),
            next = step.next,
            stepData = step.stepData,
            authData = channelService.authDataFor(channel)
        )
    }

    /**
     * InProgress/Failed/Completed -> journey transition + API response. Returns the same
     * [ChannelResponse] envelope as every other endpoint (docs/05-api.md #2). The context carries
     * stable ids only; entities are resolved fresh in this method's own transaction.
     */
    override fun applyOutcome(context: AuthorizedToolContext, outcome: ToolOutcome): ChannelResponse {
        val ctx = context as Context
        val journey = resolveJourney(ctx)
        val live = resolveChannel(ctx, journey)
        val channel = live.session
        val descriptor = toolRegistry.descriptorOf(ToolId(ctx.toolId))
        chargeThrottles(channel.accountId, descriptor.role.category, outcome)
        // A completed tool is done for good: its ToolSession must not be completable again, even
        // while the journey keeps running (an action's resumeState can still name it as active).
        if (outcome is ToolOutcome.Completed) sessionManagementService.endToolSession(ctx.toolSessionId, ToolSessionStatus.DONE)

        val step = journeyService.applyOutcome(journey, live, descriptor, outcome)

        // step.demo comes from the tool's own ToolOutcome.InProgress.demo - a field of its own, so
        // it never mixes with stepData (docs/05-api.md #2: production contract).
        return ChannelResponse(
            channel = channelService.buildChannelBlock(channel),
            next = step.next,
            stepData = step.stepData,
            demo = demoInfo(journey, channel, step.demo),
            authData = channelService.authDataFor(channel)
        )
    }

    override fun matchesAttestedIdentity(context: AuthorizedToolContext, personId: String): Boolean {
        val ctx = context as Context
        val journey = resolveJourney(ctx)
        return journeyService.matchesAttestedIdentity(journey, resolveChannel(ctx, journey).session, personId)
    }

    override fun isLockedOut(accountId: Long?): Boolean =
        accountId?.let { loginThrottleService.isLocked(it) } ?: false

    override fun isIdentLockedOut(personId: String?): Boolean =
        personId?.let { identThrottleService.isLocked(it) } ?: false

    override fun isSendThrottled(accountId: Long?): Boolean =
        accountId?.let { sendThrottleService.isThrottled(it) } ?: false

    override fun isSendThrottledForContact(contact: String): Boolean =
        sendThrottleService.isThrottledForContact(contact)

    /**
     * Charges the brute-force counter that matches what this tool actually attempted.
     *
     * The subject is read from the OUTCOME first and from the channel only as a fallback, which
     * is what makes lookup login countable at all: there, `channel.accountId` is null on failure
     * (nothing was proven) and still null on success at this point (`bindAccount` runs later,
     * inside `journeyService.applyOutcome`). Keying on the channel alone - as this did before -
     * meant every lookup attempt, failed and successful alike, went uncounted.
     */
    private fun chargeThrottles(channelAccountId: Long?, category: ToolCategory, outcome: ToolOutcome) {
        if (outcome is ToolOutcome.InProgress) return

        when (category) {
            ToolCategory.AUTH -> {
                val accountId = when (outcome) {
                    is ToolOutcome.Completed.Authenticated -> outcome.accountId ?: channelAccountId
                    is ToolOutcome.Failed -> outcome.attemptedAccountId ?: channelAccountId
                    else -> channelAccountId
                }
                accountId?.let {
                    if (outcome is ToolOutcome.Completed) loginThrottleService.recordSuccess(it)
                    else loginThrottleService.recordFailure(it)
                }
            }

            // A guessed Freischaltcode/PIN is a credential guess like any other, and its payoff
            // is higher than a login's: success adopts the person's account outright.
            ToolCategory.IDENT -> {
                val personId = when (outcome) {
                    is ToolOutcome.Completed.Identified -> outcome.personId
                    is ToolOutcome.Failed -> outcome.attemptedPersonId
                    else -> null
                }
                personId?.let {
                    if (outcome is ToolOutcome.Completed) identThrottleService.recordSuccess(it)
                    else identThrottleService.recordFailure(it)
                }
            }

            // Nothing is guessed during an enrollment - the user chooses the credential.
            ToolCategory.ENROLL -> Unit

            // Approves/declines a request that belongs to a DIFFERENT channel's account - nothing
            // about the approving account's own credentials is guessed here. Brute-forcing the
            // pending request itself (pairingCode) is a separate concern with its own protection
            // (docs/07-betrieb.md #5), not this account's login throttle.
            ToolCategory.SIDE_ACTION -> Unit

            // Same reasoning as ENROLL: the code is sent to the address being claimed, so there is
            // no existing secret to guess your way into. The ToolSession's own retry budget bounds
            // the guessing of that one code.
            ToolCategory.ATTEST -> Unit
        }
    }

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown. */
    override fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse {
        val ctx = context as Context
        val journey = resolveJourney(ctx)
        val channel = resolveChannel(ctx, journey).session
        val next = if (freshOutcome != null) {
            Next.tool(ctx.toolId, freshOutcome.nextStep, ctx.toolSessionId)
        } else {
            journeyService.nextOf(journey, channel)
        }
        // A resumed tool (e.g. after a reload) must still get the demo hints its ORIGINAL
        // activation attached (prefilled password, the persona picker): this GET is the only
        // response a resume ever sees for an active tool. Since ToolOutcome reports step data and
        // demo values separately, there is nothing left to split here.
        return ChannelResponse(
            channel = channelService.buildChannelBlock(channel),
            next = next,
            stepData = freshOutcome?.stepData,
            demo = demoInfo(journey, channel, freshOutcome?.demo),
            authData = channelService.authDataFor(channel)
        )
    }

    /** Only a running journey - a finished one takes no more tool results (docs/invarianten.md I-2). */
    private fun resolveJourney(context: Context): RunningJourney =
        journeyService.findRunning(context.journeyId)
            ?: throw OrchestratorException.processGone(Text("Journey for this tool session is gone"))

    /** LOGGED_OUT/EXPIRED is final (docs/02-domaenenmodell.md #3): no tool may move it again - see [LiveChannel]. */
    private fun resolveChannel(context: Context, journey: RunningJourney): LiveChannel {
        val journeyChannelId = journey.channelSessionId
        if (journeyChannelId != context.channelSessionId) {
            throw OrchestratorException.invalidState(Text("Tool context no longer matches its journey channel"))
        }
        return channelAccessGuard.requireLiveChannel(journeyChannelId, context.bindingKeyRef)
    }

    /**
     * personId is read back off the account rather than carried along - it is already stored there.
     *
     * `persons` (every register persona) is attached here, once, for every tool - not by each
     * tool's own `demo` values - so a frontend persona picker works everywhere without
     * touching auth_sms/auth_email/auth_password/id_fsc/id_eid individually.
     */
    private fun demoInfo(journey: RunningJourney, channel: ChannelSession, values: Map<String, Any?>?): DemoInfo? {
        val journeys = journeyService.debugChain(channel)
        val personId = journey.accountId?.let { accountService.findAccount(it)?.personId }
        return demoDisclosure.assemble(
            accountId = journey.accountId,
            personId = personId,
            journeys = journeys,
            values = values,
            // An authenticated channel gets the block even with nothing left to say - the client's
            // completed-view reads accountId/personId off it.
            includeWhenEmpty = channel.state == ChannelState.AUTHENTICATED,
            session = channelService.demoSession(channel)
        )
    }

    companion object {
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
    }
}
