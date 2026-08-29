package com.example.dpop.tool_api

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
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
    @field:Schema(example = "tool")
    val type: String,
    @field:Schema(example = "auth-sms")
    val toolId: String? = null,
    @field:Schema(example = "authentication")
    val context: String? = null,
    @field:Schema(example = "auth")
    val step: String,
    val toolSessionId: UUID? = null
) {
    companion object {
        fun tool(toolId: String, step: String, toolSessionId: UUID? = null) =
            Next(type = "tool", toolId = toolId, step = step, toolSessionId = toolSessionId)
        fun orchestrator(context: String, step: String) = Next(type = "orchestrator", context = context, step = step)

        /**
         * The one `next` every AUTHENTICATED channel with no journey pending resolves to
         * (docs/05-api.md #2) - a single named value instead of three call sites each spelling out
         * the same `context`/`step` pair by hand, two constructing it and one comparing against it.
         */
        val AUTHENTICATED = orchestrator("authentication", "authenticated")
    }
}
