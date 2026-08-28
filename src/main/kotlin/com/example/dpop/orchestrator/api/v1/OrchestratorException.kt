package com.example.dpop.orchestrator.api.v1

import org.springframework.http.HttpStatus

/** Error contract from docs/07-betrieb.md #1: HTTP errors are reserved for disrupted flows. */
class OrchestratorException(
    val status: HttpStatus,
    val errorCode: String,
    message: String
) : RuntimeException(message) {

    companion object {
        fun notFound(message: String) =
            OrchestratorException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

        fun bindingMismatch(message: String) =
            OrchestratorException(HttpStatus.FORBIDDEN, "BINDING_MISMATCH", message)

        fun invalidState(message: String) =
            OrchestratorException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", message)

        /** Process expired/consumed, or aborted after exhausted retries. */
        fun processGone(message: String) =
            OrchestratorException(HttpStatus.GONE, "PROCESS_GONE", message)

        /** Required level unreachable with the account's current methods (docs/04-orchestrierung.md #1). */
        fun processAborted(message: String) =
            OrchestratorException(HttpStatus.GONE, "PROCESS_ABORTED", message)

        /**
         * Account-level brute-force throttle tripped (LoginThrottleService) - independent of any
         * single ToolSession. Only ever raised where the account is ALREADY established for the
         * caller (a DEVICE_AUTH tool on a channel that knows its account); a lookup-based tool
         * must fold its lock into the tool's ordinary failure instead, or this response becomes
         * an account-existence oracle.
         */
        fun accountLocked(message: String) =
            OrchestratorException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", message)

        /** Rate limit on an unauthenticated, cheap-to-repeat operation (ChannelCreationThrottleService). */
        fun tooManyRequests(message: String) =
            OrchestratorException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message)
    }
}
