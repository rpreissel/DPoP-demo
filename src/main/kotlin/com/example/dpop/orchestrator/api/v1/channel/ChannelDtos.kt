package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.orchestrator.orchestration.Next
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Both fields optional: channelSessionId is accepted for symmetry but resolution always keys off the DPoP-bound binding key (docs/09-dpop.md #3); requiredAcr is a lower bound only.")
data class ChannelCreateRequest(
    val channelSessionId: UUID? = null,
    val requiredAcr: String? = null
)

@Schema(description = "Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md #9).")
data class ChannelPatchRequest(
    @field:Schema(example = "loa3")
    val requiredAcr: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelResponse(
    val channelSessionId: UUID,
    val state: String,
    val currentAcr: String? = null,
    val currentAmr: List<String>? = null,
    val stepData: Map<String, Any?>? = null,
    val next: Next? = null
)
