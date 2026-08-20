package com.example.dpop.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
@JvmRecord
data class OrchestratorResponse(
    val channelSessionId: UUID?,
    val processState: ProcessState?,
    val attemptState: AttemptState?,
    val next: NextRouting?,
    val _demo: DemoHints?
) {
    constructor(channelSessionId: UUID?, next: NextRouting?) :
        this(channelSessionId, null, null, next, null)

    constructor(channelSessionId: UUID?, processState: ProcessState?, next: NextRouting?) :
        this(channelSessionId, processState, null, next, null)

    constructor(
        channelSessionId: UUID?,
        processState: ProcessState?,
        attemptState: AttemptState?,
        next: NextRouting?
    ) : this(channelSessionId, processState, attemptState, next, null)

    /** Only present in demo/test mode — never included in production responses. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class DemoHints(
        val tan: String?,
        val note: String?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class ProcessState(
        val purpose: String?,
        val status: String?,
        val personId: Long?,
        val accountId: Long?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class AttemptState(
        val attemptId: UUID?,
        val attemptType: String?,
        val status: String?,
        val missingFields: List<String>?,
        val result: Any?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JvmRecord
    data class NextRouting(
        val context: String?,
        val step: String?,
        val methods: List<String>?,
        val enrollmentRef: String?,
        val accountId: Long?,
        val personId: Long?
    ) {
        constructor(context: String?, step: String?) :
            this(context, step, null, null, null, null)

        constructor(context: String?, step: String?, methods: List<String>?) :
            this(context, step, methods, null, null, null)

        constructor(context: String?, step: String?, methods: List<String>?, enrollmentRef: String?) :
            this(context, step, methods, enrollmentRef, null, null)
    }
}
