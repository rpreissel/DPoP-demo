package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.journey.AuthJourney
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.LoginThrottleService
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.ToolSession
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
    private val channelService: ChannelService,
    private val journeyService: JourneyService
) : ToolEndpoint {
    data class Context(val toolSession: ToolSession, val journey: AuthJourney, val channel: ChannelSession) : ToolContext {
        override val toolSessionId: UUID get() = toolSession.toolSessionId!!
        override val journeyAccountId: Long? get() = journey.accountId
        override val channelAccountId: Long? get() = channel.accountId
    }

    override fun activationLocation(baseUri: URI, toolSessionId: UUID, toolId: String): URI =
        UriComponentsBuilder.fromUri(baseUri)
            .replacePath("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
            .buildAndExpand(toolSessionId, toolId)
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

        validatePreconditions(toolId, channel.accountId)
        // Only AUTH tools authenticate an EXISTING account against a submitted credential -
        // IDENT/ENROLL failures aren't a brute-force target the same way (no credential guessed).
        if (descriptor.category == ToolCategory.AUTH) {
            channel.accountId?.let { loginThrottleService.assertNotLocked(it) }
        }

        val toolSession = sessionManagementService.createToolSession(journey.journeyId!!, TOOL_TTL)
        journeyService.activate(journey, descriptor, toolSession.toolSessionId!!)
        return Context(toolSession, journey, channel)
    }

    /**
     * [JourneyService.activate] only checks that the current state offers the tool. It does not,
     * and structurally cannot, know about preconditions a tool carries for itself. Without this,
     * a client could activate a gated tool (enroll-password, which needs a confirmed email)
     * directly, bypassing the fact that the candidate list silently excluded it.
     */
    private fun validatePreconditions(toolId: String, accountId: Long?) {
        val descriptor = toolRegistry.descriptorOf(toolId)
        if (!descriptor.requiresConfirmedEmail) return
        val confirmed = accountId?.let { accountService.findAccount(it)?.emailConfirmed } ?: false
        if (!confirmed) {
            throw OrchestratorException.invalidState("$toolId requires a confirmed account email first")
        }
    }

    override fun loadContext(toolSessionId: UUID, bindingKeyRef: String): Context {
        val toolSession = sessionManagementService.findToolSessionById(toolSessionId)
            ?: throw OrchestratorException.notFound("Tool session not found: $toolSessionId")
        val journey = journeyService.findById(toolSession.journeyId!!)
            ?: throw OrchestratorException.processGone("Journey for this tool session is gone")
        val channel = channelAccessGuard.requireChannel(journey.channelSessionId!!, bindingKeyRef)
        return Context(toolSession, journey, channel)
    }

    override fun requireCurrentTool(context: ToolContext, toolId: String) {
        if (!isCurrentTool(context, toolId)) {
            throw OrchestratorException.invalidState("$toolId is not the currently active tool for this journey")
        }
    }

    override fun isCurrentTool(context: ToolContext, toolId: String): Boolean {
        val ctx = context as Context
        return journeyService.isCurrent(ctx.journey, toolId, ctx.toolSession.toolSessionId!!)
    }

    /**
     * "Back"/"Switch". Invalidates the ToolSession immediately rather than waiting for its TTL: a
     * re-offered candidate can be the same toolId as the one being abandoned, so toolId matching
     * alone can't tell the abandoned session and a freshly re-activated one apart.
     */
    override fun abandon(context: ToolContext, toolId: String): ChannelResponse {
        val ctx = context as Context
        sessionManagementService.expireToolSession(ctx.toolSessionId)
        val step = journeyService.abandon(ctx.journey, ctx.channel, toolRegistry.descriptorOf(toolId))
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
    override fun applyOutcome(toolId: String, outcome: ToolOutcome, context: ToolContext): ChannelResponse {
        val ctx = context as Context
        val descriptor = toolRegistry.descriptorOf(toolId)
        if (descriptor.category == ToolCategory.AUTH && outcome !is ToolOutcome.InProgress) {
            ctx.channel.accountId?.let {
                if (outcome is ToolOutcome.Completed) loginThrottleService.recordSuccess(it)
                else loginThrottleService.recordFailure(it)
            }
        }

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
            demo = demoInfo(ctx.journey, step.next, demoValues)
        )
    }

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown. */
    override fun buildReadResponse(
        toolSessionId: UUID,
        toolId: String,
        context: ToolContext,
        freshOutcome: ToolOutcome.InProgress?
    ): ChannelResponse {
        val ctx = context as Context
        val next = if (freshOutcome != null) {
            Next.tool(toolId, freshOutcome.nextStep, toolSessionId)
        } else {
            journeyService.nextOf(ctx.journey)
        }
        return ChannelResponse(
            channel = channelService.buildChannelBlock(ctx.channel),
            next = next,
            stepData = freshOutcome?.data
        )
    }

    /** personId is read back off the account rather than carried along - it is already stored there. */
    private fun demoInfo(journey: AuthJourney, next: Next, values: Map<String, Any?>?): DemoInfo? {
        val isAuthenticated = next.context == "authentication" && next.step == "authenticated"
        if (!isAuthenticated && values.isNullOrEmpty()) return null
        val personId = journey.accountId?.let { accountService.findAccount(it)?.personId }
        return DemoInfo(journey.accountId, personId, values ?: emptyMap())
    }

    companion object {
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
    }
}
