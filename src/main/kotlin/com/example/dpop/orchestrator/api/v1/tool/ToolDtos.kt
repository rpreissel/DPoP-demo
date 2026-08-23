package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.orchestration.Next
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolStateResponse(
    val toolSessionId: UUID,
    val stepData: Map<String, Any?>? = null,
    val next: Next,
    @field:Schema(description = "Demo-only correlation IDs, never part of the production contract (docs/05-api.md #2).")
    val demo: DemoInfo? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DemoInfo(val accountId: Long? = null, val personId: Long? = null, val tan: String? = null)
