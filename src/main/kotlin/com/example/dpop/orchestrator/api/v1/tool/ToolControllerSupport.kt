package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.journey.AuthJourney
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ChannelState
import com.example.dpop.orchestrator.session.IdentThrottleService
import com.example.dpop.orchestrator.session.LoginThrottleService
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.ToolSession
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.DemoInfo
import com.example.dpop.tool_api.Next
import com.example.dpop.tool_api.ToolContext
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.DEMO_DATA_KEY
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Plumbing shared by every tool-specific controller: binding check, throttling, tool-session
 * lifecycle, response envelope. Each controller stays tool-specific
 * (docs/08-projektrahmen.md A11); only this cross-cutting part is centralized so it can't
 * silently diverge between tools.
 *
 * Implements [ToolEndpoint] - the SPI a tool controller is written against once it moves into its
 * own method module (DPoP-demo-2tm). [Context] implements [ToolContext] but is never exposed as
 * more than that interface to any caller (not even `ToolSwitchController`, which now lives in
 * `tool_api` and only ever sees [ToolContext] too - see [abandon]); the methods whose interface
 * signature takes `context: ToolContext` cast it back to [Context] internally, safe because this
 * class is the only place a [Context] is ever constructed.
 *
 * What is deliberately NOT here any more: any knowledge of which tool may run when. That is a
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
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val toolAvailabilityService: ToolAvailabilityService
) : ToolEndpoint {
    data class Context(override val toolId: String, val toolSession: ToolSession, val journey: AuthJourney, val channel: ChannelSession) : ToolContext {
        override val toolSessionId: UUID get() = toolSession.toolSessionId!!
        override val journeyAccountId: Long? get() = journey.accountId
        override val channelAccountId: Long? get() = channel.accountId
    }

    override fun activationLocation(context: ToolContext, baseUri: URI): URI =
        UriComponentsBuilder.fromUri(baseUri)
            .replacePath("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
            .buildAndExpand(context.toolSessionId, context.toolId)
            .toUri()

    /**
     * Mints the ToolSession and lets the journey decide whether [toolId] may run at all. The
     * check is membership in the current state's own offer, so a tool that was never offered
     * cannot be activated by naming it.
     */
    override fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String): Context {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val journey = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active journey for this channel")
        val descriptor = toolRegistry.descriptorOf(toolId)

        validatePreconditions(toolId, channel)
        // Only reachable for a tool whose account the CHANNEL already knows - i.e. a DEVICE_AUTH
        // tool. A LOOKUP_AUTH tool has no accountId here by definition (it resolves one from
        // submitted input later), and an IDENT tool has none at all; those two are throttled at
        // the point they resolve their own subject, via isLockedOut/isIdentLockedOut, and answer
        // with their ordinary failure instead of this explicit 423.
        if (descriptor.role.category == ToolCategory.AUTH) {
            channel.accountId?.let { loginThrottleService.assertNotLocked(it) }
        }

        val toolSession = sessionManagementService.createToolSession(journey.journeyId!!, TOOL_TTL)
        journeyService.activate(journey, channel, descriptor, toolSession.toolSessionId!!)
        return Context(toolId, toolSession, journey, channel)
    }

    /**
     * [JourneyService.activate] only checks that the current state offers the tool. It does not,
     * and structurally cannot, know about preconditions a tool carries for itself. Without this,
     * a client could activate a gated tool (enroll-password, which needs a confirmed email)
     * directly, bypassing the fact that the candidate list silently excluded it.
     */
    private fun validatePreconditions(toolId: String, channel: ChannelSession) {
        // Same reasoning as requiresConfirmedEmail below: JourneyService.activate only checks
        // membership in the current state's offer, which already excludes unavailable tools - but a
        // direct activation call must be re-checked here, defensively, against both availability
        // axes (docs/03-tool-architektur.md, availability).
        if (toolId !in channel.availableClientTools || !toolAvailabilityService.isEnabled(toolId)) {
            throw OrchestratorException.invalidState("$toolId is not available on this channel")
        }

        val descriptor = toolRegistry.descriptorOf(toolId)
        if (!descriptor.requiresConfirmedEmail) return
        val confirmed = channel.accountId?.let { accountService.findAccount(it)?.emailConfirmed } ?: false
        if (!confirmed) {
            throw OrchestratorException.invalidState("$toolId requires a confirmed account email first")
        }
    }

    override fun loadContext(toolSessionId: UUID, bindingKeyRef: String, toolId: String): Context {
        val toolSession = sessionManagementService.findToolSessionById(toolSessionId)
            ?: throw OrchestratorException.notFound("Tool session not found: $toolSessionId")
        val journey = journeyService.findById(toolSession.journeyId!!)
            ?: throw OrchestratorException.processGone("Journey for this tool session is gone")
        val channel = channelAccessGuard.requireChannel(journey.channelSessionId!!, bindingKeyRef)
        return Context(toolId, toolSession, journey, channel)
    }

    override fun requireCurrentTool(context: ToolContext) {
        if (!isCurrentTool(context)) {
            throw OrchestratorException.invalidState("${context.toolId} is not the currently active tool for this journey")
        }
    }

    override fun isCurrentTool(context: ToolContext): Boolean {
        val ctx = context as Context
        return journeyService.isCurrent(ctx.journey, ctx.toolId, ctx.toolSession.toolSessionId!!)
    }

    /**
     * "Back"/"Switch". Invalidates the ToolSession immediately rather than waiting for its TTL: a
     * re-offered candidate can be the same toolId as the one being abandoned, so toolId matching
     * alone can't tell the abandoned session and a freshly re-activated one apart.
     */
    override fun abandon(context: ToolContext): ChannelResponse {
        val ctx = context as Context
        sessionManagementService.expireToolSession(ctx.toolSessionId)
        val step = journeyService.abandon(ctx.journey, ctx.channel, toolRegistry.descriptorOf(ctx.toolId))
        return ChannelResponse(
            channel = channelService.buildChannelBlock(ctx.channel),
            next = step.next,
            stepData = step.stepData
        )
    }

    /**
     * InProgress/Failed/Completed -> journey transition + API response. Returns the same
     * [ChannelResponse] envelope as every other endpoint (docs/05-api.md #2): [Context.channel]
     * is the same mutable entity [JourneyService] just updated in place, so the block reflects
     * the post-outcome state without a separate `GET /channels`.
     */
    override fun applyOutcome(context: ToolContext, outcome: ToolOutcome): ChannelResponse {
        val ctx = context as Context
        val descriptor = toolRegistry.descriptorOf(ctx.toolId)
        chargeThrottles(ctx, descriptor.role.category, outcome)

        val step = journeyService.applyOutcome(ctx.journey, ctx.channel, descriptor, outcome)

        // The DEMO_DATA_KEY bag travels inside ToolOutcome.data like any other tool-internal
        // field, but never belongs in stepData (docs/05-api.md #2: production contract).
        @Suppress("UNCHECKED_CAST")
        val demoValues = step.stepData?.get(DEMO_DATA_KEY) as? Map<String, Any?>
        val cleanedStepData = step.stepData?.minus(DEMO_DATA_KEY)?.ifEmpty { null }
        return ChannelResponse(
            channel = channelService.buildChannelBlock(ctx.channel),
            next = step.next,
            stepData = cleanedStepData,
            demo = demoInfo(ctx.journey, ctx.channel, demoValues)
        )
    }

    override fun isLockedOut(accountId: Long?): Boolean =
        accountId?.let { loginThrottleService.isLocked(it) } ?: false

    override fun isIdentLockedOut(personId: Long?): Boolean =
        personId?.let { identThrottleService.isLocked(it) } ?: false

    /**
     * Charges the brute-force counter that matches what this tool actually attempted.
     *
     * The subject is read from the OUTCOME first and from the channel only as a fallback, which
     * is what makes lookup login countable at all: there, `channel.accountId` is null on failure
     * (nothing was proven) and still null on success at this point (`bindAccount` runs later,
     * inside `journeyService.applyOutcome`). Keying on the channel alone - as this did before -
     * meant every lookup attempt, failed and successful alike, went uncounted.
     */
    private fun chargeThrottles(ctx: Context, category: ToolCategory, outcome: ToolOutcome) {
        if (outcome is ToolOutcome.InProgress) return

        when (category) {
            ToolCategory.AUTH -> {
                val accountId = when (outcome) {
                    is ToolOutcome.Completed.Authenticated -> outcome.accountId ?: ctx.channel.accountId
                    is ToolOutcome.Failed -> outcome.attemptedAccountId ?: ctx.channel.accountId
                    else -> ctx.channel.accountId
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
        }
    }

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown. */
    override fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse {
        val ctx = context as Context
        val next = if (freshOutcome != null) {
            Next.tool(ctx.toolId, freshOutcome.nextStep, ctx.toolSessionId)
        } else {
            journeyService.nextOf(ctx.journey, ctx.channel)
        }
        return ChannelResponse(
            channel = channelService.buildChannelBlock(ctx.channel),
            next = next,
            stepData = freshOutcome?.data
        )
    }

    /** personId is read back off the account rather than carried along - it is already stored there. */
    private fun demoInfo(journey: AuthJourney, channel: ChannelSession, values: Map<String, Any?>?): DemoInfo? {
        val isAuthenticated = channel.state == ChannelState.AUTHENTICATED
        if (!isAuthenticated && values.isNullOrEmpty()) return null
        val personId = journey.accountId?.let { accountService.findAccount(it)?.personId }
        return DemoInfo(journey.accountId, personId, values ?: emptyMap())
    }

    companion object {
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
    }
}
