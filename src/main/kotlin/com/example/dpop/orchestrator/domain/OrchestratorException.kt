package com.example.dpop.orchestrator.domain

import com.example.dpop.texts.Text

/** Error contract from docs/07-betrieb.md #1: HTTP errors are reserved for disrupted flows. */
class OrchestratorException(
    val code: ErrorCode,
    /** What the caller is told, as a text reference (docs/adr/ADR-033). */
    val text: Text,
    /** What only the log gets - ids and the like, useless to a reader and not theirs to see. */
    detail: String? = null
) : RuntimeException(detail?.let { "${text.template} ($it)" } ?: text.template) {

    companion object {
        fun notFound(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.NOT_FOUND, text, detail)

        fun bindingMismatch(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.BINDING_MISMATCH, text, detail)

        fun invalidState(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.INVALID_STATE_TRANSITION, text, detail)

        /** Process expired/consumed, or aborted after exhausted retries. */
        fun processGone(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.PROCESS_GONE, text, detail)

        /** Required level unreachable with the account's current methods (docs/04-orchestrierung.md #1). */
        fun processAborted(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.PROCESS_ABORTED, text, detail)

        /**
         * Account-level brute-force throttle tripped (LoginThrottleService) - independent of any
         * single ToolSession. Only ever raised where the account is ALREADY established for the
         * caller (a IDENTIFIED_AUTH tool on a channel that knows its account); a lookup-based tool
         * must fold its lock into the tool's ordinary failure instead, or this response becomes
         * an account-existence oracle.
         */
        fun accountLocked(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.ACCOUNT_LOCKED, text, detail)

        /** Rate limit on an unauthenticated, cheap-to-repeat operation (ChannelCreationThrottleService). */
        fun tooManyRequests(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.TOO_MANY_REQUESTS, text, detail)
    }
}
