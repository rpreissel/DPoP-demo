package com.example.dpop.orchestrator.orchestration

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * A pure address, never mixed with content (docs/05-api.md #2). Either a concrete tool step
 * (type=tool, toolId+step+toolSessionId) or a selection/completion page (type=flow, context+step).
 * [toolSessionId] is the full address of the tool resource (docs/05-api.md #2:
 * `/tools/{toolSessionId}/{toolId}`) and is only known once a ToolSession actually exists for
 * this step - null right after a selection page, before the client has activated anything.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Next(
    val type: String,
    val toolId: String? = null,
    val context: String? = null,
    val step: String,
    val toolSessionId: UUID? = null
) {
    companion object {
        fun tool(toolId: String, step: String, toolSessionId: UUID? = null) =
            Next(type = "tool", toolId = toolId, step = step, toolSessionId = toolSessionId)
        fun flow(context: String, step: String) = Next(type = "flow", context = context, step = step)
    }
}
