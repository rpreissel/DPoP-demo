package com.example.dpop.orchestrator.orchestration

import com.example.dpop.orchestrator.api.v1.OrchestratorException
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry

/**
 * Shared skip-if-single-candidate rule (docs/04-orchestrierung.md #1 table, docs/05-api.md #2):
 * one candidate points straight at the tool, several open a selection page, none aborts the
 * process - used both right after a Completed outcome and when a LOGIN first picks its tool.
 */
object CandidateOffering {
    data class Offer(val next: Next, val stepData: Map<String, Any?>? = null)

    fun resolve(
        toolRegistry: ToolHandlerRegistry,
        candidates: List<String>,
        context: String,
        selectStep: String = "selectMethod"
    ): Offer = when {
        candidates.isEmpty() -> throw OrchestratorException.processAborted(
            "Gefordertes Sicherheitsniveau ist mit den vorhandenen Methoden nicht erreichbar"
        )
        candidates.size == 1 -> {
            val toolId = candidates.single()
            Offer(Next.tool(toolId, toolRegistry.descriptorOf(toolId).startStep))
        }
        else -> Offer(Next.flow(context, selectStep), mapOf("options" to candidates))
    }
}
