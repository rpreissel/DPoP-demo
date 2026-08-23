package com.example.dpop.orchestrator.orchestration

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A pure address, never mixed with content (docs/05-api.md #2). Either a concrete tool step
 * (type=tool, toolId+step) or a selection/completion page (type=flow, context+step).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Next(
    val type: String,
    val toolId: String? = null,
    val context: String? = null,
    val step: String
) {
    companion object {
        fun tool(toolId: String, step: String) = Next(type = "tool", toolId = toolId, step = step)
        fun flow(context: String, step: String) = Next(type = "flow", context = context, step = step)
    }
}
