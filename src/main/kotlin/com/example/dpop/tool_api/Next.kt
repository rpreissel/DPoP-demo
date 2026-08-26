package com.example.dpop.tool_api

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

/**
 * A pure address, never mixed with content (docs/05-api.md #2). Both values of [type] answer the
 * same question - who owns the next screen, and which endpoint the client calls next:
 * `tool` (toolId+step+toolSessionId, talk to /tools/...) or `orchestrator` (context+step, a
 * selection or completion page served by /channels/...).
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
        fun orchestrator(context: String, step: String) = Next(type = "orchestrator", context = context, step = step)
    }
}
