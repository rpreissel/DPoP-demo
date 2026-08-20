package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.session.ChannelState
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelSessionRequest(
    val channel: String?,
    val data: Map<String, Any>?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelSessionResponse(
    val channelSessionId: UUID?,
    val state: ChannelState?,
    val currentAcr: String?,
    val currentAmr: List<String>?,
    val stepUpRequired: Boolean?,
    val accountId: Long?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AttemptRequest(
    val method: String?,
    val mode: String? = null,
    val data: Map<String, Any>?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OrchestratorResponse(
    val channelSessionId: UUID?,
    val processState: ProcessState? = null,
    val attemptState: AttemptState? = null,
    val next: NextRouting?,
    val _demo: DemoHints? = null
) {
    /** Only present in demo/test mode — never included in production responses. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class DemoHints(
        val tan: String?,
        val note: String?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ProcessState(
        val purpose: String?,
        val status: String?,
        val personId: Long?,
        val accountId: Long?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class AttemptState(
        val attemptId: UUID?,
        val attemptType: String?,
        val status: String?,
        val missingFields: List<String>?,
        val result: Any?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class NextRouting(
        val context: String?,
        val step: String?,
        val methods: List<String>? = null,
        val enrollmentRef: String? = null,
        val accountId: Long? = null,
        val personId: Long? = null
    )
}
