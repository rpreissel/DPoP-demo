package com.example.dpop.orchestrator.kernel

/** Error contract from docs/07-betrieb.md #1: HTTP errors are reserved for disrupted flows. */
class OrchestratorException(
    val code: ErrorCode,
    message: String
) : RuntimeException(message) {

    companion object {
        fun notFound(message: String) =
            OrchestratorException(ErrorCode.NOT_FOUND, message)

        fun bindingMismatch(message: String) =
            OrchestratorException(ErrorCode.BINDING_MISMATCH, message)

        fun invalidState(message: String) =
            OrchestratorException(ErrorCode.INVALID_STATE_TRANSITION, message)

        /** Process expired/consumed, or aborted after exhausted retries. */
        fun processGone(message: String) =
            OrchestratorException(ErrorCode.PROCESS_GONE, message)

        /** Required level unreachable with the account's current methods (docs/04-orchestrierung.md #1). */
        fun processAborted(message: String) =
            OrchestratorException(ErrorCode.PROCESS_ABORTED, message)

        /**
         * Account-level brute-force throttle tripped (LoginThrottleService) - independent of any
         * single ToolSession. Only ever raised where the account is ALREADY established for the
         * caller (a IDENTIFIED_AUTH tool on a channel that knows its account); a lookup-based tool
         * must fold its lock into the tool's ordinary failure instead, or this response becomes
         * an account-existence oracle.
         */
        fun accountLocked(message: String) =
            OrchestratorException(ErrorCode.ACCOUNT_LOCKED, message)

        /** Rate limit on an unauthenticated, cheap-to-repeat operation (ChannelCreationThrottleService). */
        fun tooManyRequests(message: String) =
            OrchestratorException(ErrorCode.TOO_MANY_REQUESTS, message)
    }
}
