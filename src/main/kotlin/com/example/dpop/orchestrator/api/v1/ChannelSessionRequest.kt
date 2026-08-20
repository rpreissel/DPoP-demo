package com.example.dpop.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class ChannelSessionRequest(
    val channel: String?,
    val data: Map<String, Any>?
)
