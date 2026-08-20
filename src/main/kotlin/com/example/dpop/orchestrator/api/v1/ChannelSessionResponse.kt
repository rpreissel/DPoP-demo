package com.example.dpop.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonInclude
import com.example.dpop.orchestrator.session.ChannelState
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class ChannelSessionResponse(
    val channelSessionId: UUID?,
    val state: ChannelState?,
    val currentAcr: String?,
    val currentAmr: List<String>?,
    val stepUpRequired: Boolean?,
    val accountId: Long?
)
