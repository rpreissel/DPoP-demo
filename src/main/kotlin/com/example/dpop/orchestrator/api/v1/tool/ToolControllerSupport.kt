package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.api.v1.channel.DemoInfo
import com.example.dpop.orchestrator.orchestration.Next
import com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor
import com.example.dpop.orchestrator.orchestration.ToolSteps
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.LoginThrottleService
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.RegistrationProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.ToolSession
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
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
 * Plumbing shared by every tool-specific controller and by activation (docs/06-ablaeufe.md:
 * "Orchestrator übernimmt API-Routing, Tool-Lifecycle-Steuerung..." - identical for every
 * tool). Each controller stays tool-specific (docs/08-projektrahmen.md A11); only this
 * cross-cutting, security-relevant part (binding check, retry counting, next persistence) is
 * centralized so it can't silently diverge between tools.
 */
@Service
@Transactional
class ToolControllerSupport(
    private val sessionManagementService: SessionManagementService,
    private val toolOutcomeProcessor: ToolOutcomeProcessor,
    private val channelAccessGuard: ChannelAccessGuard,
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService,
    private val loginThrottleService: LoginThrottleService,
    private val channelService: ChannelService
) {
    data class Context(val toolSession: ToolSession, val processSession: ProcessSession, val channel: ChannelSession)

    /** The `Location` of a just-created tool resource (docs/05-api.md #2) - identical across every tool's activate(), centralized so 10 controllers don't each rebuild the same URI. */
    fun activationLocation(uriBuilder: UriComponentsBuilder, toolSessionId: UUID, toolId: String): URI =
        uriBuilder.replacePath("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}").buildAndExpand(toolSessionId, toolId).toUri()

    /**
     * Validates that [toolId] is actually due next for this channel and mints its ToolSession
     * (docs/05-api.md #2: "Die Aktivierung bleibt Orchestrator-Hoheit und ist für alle Tools
     * gleich"). Called by each tool's own controller, not through a generic dispatcher.
     */
    fun beginActivation(channelSessionId: UUID, bindingKeyRef: String, toolId: String, category: ToolCategory): Context {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
        val processSession = sessionManagementService.findActiveProcessSession(channelSessionId)
            ?: throw OrchestratorException.invalidState("No active process for this channel")
        validateActivation(processSession, toolId, category)
        validatePreconditions(toolId, channel.accountId)
        // Only AUTH tools authenticate an EXISTING account against a submitted credential -
        // IDENT/ENROLL failures aren't a brute-force target the same way (no credential guessed).
        if (category == ToolCategory.AUTH) {
            channel.accountId?.let { loginThrottleService.assertNotLocked(it) }
        }
        val toolSession = sessionManagementService.createToolSession(processSession.processSessionId!!, TOOL_TTL)

        // Claim this ToolSession as THE current attempt for toolId - a concurrent/duplicate
        // activation request mints its own ToolSession too, but only the one that lands here
        // last wins; the other becomes an orphan that isCurrentTool now correctly rejects.
        // Always the tool's own start step, whether it was offered directly (nextType "tool") or
        // reached via a selection page (nextType "flow", nextStep "selectMethod" - not this tool's step).
        processSession.setNextTool(toolId, ToolSteps.startStepFor(toolId), toolSession.toolSessionId)
        sessionManagementService.updateProcessSession(processSession)

        return Context(toolSession, processSession, channel)
    }

    private fun validateActivation(processSession: ProcessSession, toolId: String, category: ToolCategory) {
        when (processSession.nextType) {
            "tool" -> if (processSession.nextToolId != toolId) {
                throw OrchestratorException.invalidState("Currently due tool is ${processSession.nextToolId}, not $toolId")
            }
            "flow" -> {
                val expected = EXPECTED_CATEGORIES_BY_CONTEXT[processSession.nextContext]
                if (expected != null && category !in expected) {
                    throw OrchestratorException.invalidState("$toolId does not match selection context ${processSession.nextContext}")
                }
            }
            else -> throw OrchestratorException.invalidState("No tool step is currently due for this channel")
        }
    }

    /**
     * [validateActivation] only checks the CATEGORY matches the current selection context (e.g.
     * "enrollment" accepts any ENROLL tool) - it does not, and structurally cannot, know which
     * specific candidates were actually offered in `stepData.options`. Without this second check,
     * a client could activate a precondition-gated tool (e.g. enroll-password, which requires a
     * confirmed email - docs/03-tool-architektur.md) directly, bypassing the fact that it was
     * silently excluded from the offer. `requiresConfirmedEmail` must be enforced here, not just
     * reflected in what AuthPolicy.enrollmentCandidates chooses to list.
     */
    private fun validatePreconditions(toolId: String, accountId: Long?) {
        val descriptor = toolRegistry.descriptorOf(toolId)
        if (!descriptor.requiresConfirmedEmail) return
        val confirmed = accountId?.let { accountService.findAccount(it)?.emailConfirmed } ?: false
        if (!confirmed) {
            throw OrchestratorException.invalidState("$toolId requires a confirmed account email first")
        }
    }

    fun loadContext(toolSessionId: UUID, bindingKeyRef: String): Context {
        val toolSession = sessionManagementService.findToolSessionById(toolSessionId)
            ?: throw OrchestratorException.notFound("Tool session not found: $toolSessionId")
        val processSession = sessionManagementService.findProcessSessionById(toolSession.processSessionId!!)
            ?: throw OrchestratorException.processGone("Process for this tool session is gone")
        val channel = channelAccessGuard.requireChannel(processSession.channelSessionId!!, bindingKeyRef)
        return Context(toolSession, processSession, channel)
    }

    fun requireCurrentTool(context: Context, toolId: String) {
        if (!isCurrentTool(context, toolId)) {
            throw OrchestratorException.invalidState("$toolId is not the currently active tool for this process")
        }
    }

    fun isCurrentTool(context: Context, toolId: String): Boolean =
        context.processSession.nextType == "tool" &&
            context.processSession.nextToolId == toolId &&
            context.processSession.nextToolSessionId == context.toolSession.toolSessionId

    fun currentProcessNext(processSession: ProcessSession): Next = when (processSession.nextType) {
        "tool" -> Next.tool(processSession.nextToolId!!, processSession.nextStep!!, processSession.nextToolSessionId)
        "flow" -> Next.flow(processSession.nextContext!!, processSession.nextStep!!)
        else -> throw IllegalStateException("Process has no routing state")
    }

    /**
     * InProgress/Failed/Completed -> persisted next + API response, incl. the retry rule
     * (docs/04-orchestrierung.md #1). Returns the same [ChannelResponse] envelope as every other
     * endpoint (docs/05-api.md #2): [context.channel] is the same mutable entity
     * [ToolOutcomeProcessor] just updated in place (state/accountId/authContextId on Completed),
     * so [ChannelService.buildChannelBlock] reflects the post-outcome state without a separate
     * `GET /channels` round-trip.
     */
    fun applyOutcome(toolId: String, outcome: ToolOutcome, context: Context): ChannelResponse {
        val category = toolRegistry.descriptorOf(toolId).category
        val (next, stepData) = when (outcome) {
            is ToolOutcome.InProgress -> {
                context.processSession.setNextTool(toolId, outcome.nextStep, context.toolSession.toolSessionId)
                sessionManagementService.updateProcessSession(context.processSession)
                Next.tool(toolId, outcome.nextStep, context.toolSession.toolSessionId) to outcome.data
            }

            is ToolOutcome.Failed -> {
                if (category == ToolCategory.AUTH) {
                    context.channel.accountId?.let { loginThrottleService.recordFailure(it) }
                }
                handleFailed(outcome.reason, context)
            }

            is ToolOutcome.Completed -> {
                if (category == ToolCategory.AUTH) {
                    context.channel.accountId?.let { loginThrottleService.recordSuccess(it) }
                }
                val result = toolOutcomeProcessor.process(toolId, outcome, context.processSession, context.channel)
                result.next to result.stepData
            }
        }

        // The DEMO_DATA_KEY bag travels inside ToolOutcome.data like any other tool-internal
        // field, but never belongs in stepData (docs/05-api.md #2: production contract) - lift it
        // into the separate `demo` object alongside accountId/personId. Generic: a handler can
        // attach any demo-only value via tool_spi.demoData(...) without this class knowing its name.
        @Suppress("UNCHECKED_CAST")
        val demoValues = stepData?.get(DEMO_DATA_KEY) as? Map<String, Any?>
        val cleanedStepData = stepData?.minus(DEMO_DATA_KEY)?.ifEmpty { null }
        // currentAcr/currentAmr/activeMethods are never part of a tool response (docs/05-api.md
        // #2) - the only production reader is the security-summary screen, which the client
        // reaches via `next` alone and then fetches explicitly (GET /channels, GET .../methods),
        // like any screen would fetch its own data on demand rather than have every response
        // carry it "just in case".
        return ChannelResponse(
            channel = channelService.buildChannelBlock(context.channel),
            next = next,
            stepData = cleanedStepData,
            demo = demoInfo(context.processSession, next, demoValues)
        )
    }

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown (docs/06-ablaeufe.md #2, step 4). */
    fun buildReadResponse(toolSessionId: UUID, toolId: String, context: Context, freshOutcome: ToolOutcome.InProgress?): ChannelResponse {
        val next = if (freshOutcome != null) {
            Next.tool(toolId, context.processSession.nextStep ?: freshOutcome.nextStep, toolSessionId)
        } else {
            currentProcessNext(context.processSession)
        }
        return ChannelResponse(
            channel = channelService.buildChannelBlock(context.channel),
            next = next,
            stepData = freshOutcome?.data
        )
    }

    private fun handleFailed(reason: String, context: Context): Pair<Next, Map<String, Any?>?> {
        val updated = sessionManagementService.registerFailedToolAttempt(context.toolSession.toolSessionId!!)
        if (updated.retryCount >= MAX_RETRIES) {
            sessionManagementService.failProcessSession(context.processSession.processSessionId!!)
            throw OrchestratorException.processGone("Retry-Limit erreicht: $reason")
        }
        return currentProcessNext(context.processSession) to mapOf("error" to reason)
    }

    private fun demoInfo(processSession: ProcessSession, next: Next, values: Map<String, Any?>?): DemoInfo? {
        val isAuthenticated = next.context == "authentication" && next.step == "authenticated"
        if (!isAuthenticated && values.isNullOrEmpty()) return null
        val personId = (processSession as? RegistrationProcessSession)?.personId
        return DemoInfo(processSession.accountId, personId, values ?: emptyMap())
    }

    companion object {
        private const val MAX_RETRIES = 3
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
        // "auth" also accepts IDENT: re-identification (ident-fsc) can be offered as a step-up
        // candidate alongside AUTH tools (docs/04-orchestrierung.md, MANAGE_METHODS) - see
        // AuthPolicy.reIdentCandidates.
        private val EXPECTED_CATEGORIES_BY_CONTEXT = mapOf(
            "registration" to setOf(ToolCategory.IDENT),
            "enrollment" to setOf(ToolCategory.ENROLL),
            "auth" to setOf(ToolCategory.AUTH, ToolCategory.IDENT)
        )
    }
}
