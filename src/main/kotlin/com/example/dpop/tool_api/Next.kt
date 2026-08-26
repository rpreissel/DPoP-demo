package com.example.dpop.tool_api

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * A pure address for the client's next step - never mixed with content.
 *
 * [type] is `"tool"` (call `/tools/{toolSessionId}/{toolId}`) or `"orchestrator"` (a selection or
 * completion page served under `/channels/...`, addressed by [context]+[step]). [toolSessionId]
 * is only present once a tool session actually exists for this step - `null` right after a
 * selection page, before the client has activated anything.
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
        fun orchestrator(context: String, step: String) = Next(type = "orchestrator", context = context, step = step)
    }
}
