package com.example.dpop.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class AttemptRequest(
    val method: String?,
    val mode: String?,
    val data: Map<String, Any>?
) {
    constructor(method: String, data: Map<String, Any>?) : this(method, null, data)
}
