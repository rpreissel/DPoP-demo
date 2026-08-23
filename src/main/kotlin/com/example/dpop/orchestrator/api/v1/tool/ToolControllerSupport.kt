package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.api.v1.ChannelAccessGuard
import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.orchestration.Next
import com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor
import com.example.dpop.orchestrator.orchestration.ToolSteps
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.orchestrator.session.ProcessSession
import com.example.dpop.orchestrator.session.RegistrationProcessSession
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.session.ToolSession
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val channelAccessGuard: ChannelAccessGuard
) {
    data class Context(val toolSession: ToolSession, val processSession: ProcessSession, val channel: ChannelSession)

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
                val expected = EXPECTED_CATEGORY_BY_CONTEXT[processSession.nextContext]
                if (expected != null && expected != category) {
                    throw OrchestratorException.invalidState("$toolId does not match selection context ${processSession.nextContext}")
                }
            }
            else -> throw OrchestratorException.invalidState("No tool step is currently due for this channel")
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
        "tool" -> Next.tool(processSession.nextToolId!!, processSession.nextStep!!)
        "flow" -> Next.flow(processSession.nextContext!!, processSession.nextStep!!)
        else -> throw IllegalStateException("Process has no routing state")
    }

    /** InProgress/Failed/Completed -> persisted next + API response, incl. the retry rule (docs/04-orchestrierung.md #1). */
    fun applyOutcome(toolId: String, outcome: ToolOutcome, context: Context): ToolStateResponse {
        val (next, stepData) = when (outcome) {
            is ToolOutcome.InProgress -> {
                context.processSession.setNextTool(toolId, outcome.nextStep, context.toolSession.toolSessionId)
                sessionManagementService.updateProcessSession(context.processSession)
                Next.tool(toolId, outcome.nextStep) to outcome.data
            }

            is ToolOutcome.Failed -> handleFailed(outcome.reason, context)

            is ToolOutcome.Completed -> {
                val result = toolOutcomeProcessor.process(toolId, outcome, context.processSession, context.channel)
                result.next to result.stepData
            }
        }

        // demoTan travels inside ToolOutcome.data like any other tool-internal field, but never
        // belongs in stepData (docs/05-api.md #2: production contract) - lift it into the
        // separate `demo` object alongside accountId/personId, same as every other demo-only value.
        val demoTan = stepData?.get(DEMO_TAN_KEY) as? String
        val cleanedStepData = if (demoTan != null) stepData.minus(DEMO_TAN_KEY) else stepData
        return ToolStateResponse(context.toolSession.toolSessionId!!, cleanedStepData, next, demoInfo(context.processSession, next, demoTan))
    }

    /** For GET: only the still-current tool's freshly rebuilt InProgress state is shown (docs/06-ablaeufe.md #2, step 4). */
    fun buildReadResponse(toolSessionId: UUID, toolId: String, context: Context, freshOutcome: ToolOutcome.InProgress?): ToolStateResponse =
        if (freshOutcome != null) {
            ToolStateResponse(toolSessionId, freshOutcome.data, Next.tool(toolId, context.processSession.nextStep ?: freshOutcome.nextStep))
        } else {
            ToolStateResponse(toolSessionId, null, currentProcessNext(context.processSession))
        }

    private fun handleFailed(reason: String, context: Context): Pair<Next, Map<String, Any?>?> {
        val updated = sessionManagementService.registerFailedToolAttempt(context.toolSession.toolSessionId!!)
        if (updated.retryCount >= MAX_RETRIES) {
            sessionManagementService.failProcessSession(context.processSession.processSessionId!!)
            throw OrchestratorException.processGone("Retry-Limit erreicht: $reason")
        }
        return currentProcessNext(context.processSession) to mapOf("error" to reason)
    }

    private fun demoInfo(processSession: ProcessSession, next: Next, tan: String? = null): DemoInfo? {
        val isAuthenticated = next.context == "authentication" && next.step == "authenticated"
        if (!isAuthenticated && tan == null) return null
        val personId = (processSession as? RegistrationProcessSession)?.personId
        return DemoInfo(processSession.accountId, personId, tan)
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val DEMO_TAN_KEY = "demoTan"
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
        private val EXPECTED_CATEGORY_BY_CONTEXT = mapOf(
            "registration" to ToolCategory.IDENT,
            "enrollment" to ToolCategory.ENROLL,
            "auth" to ToolCategory.AUTH
        )
    }
}
