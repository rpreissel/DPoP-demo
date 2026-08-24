package com.example.dpop.tool_spi

/**
 * Reserved key in [ToolOutcome.InProgress.data]: a nested bag of demo-only values (a just-issued
 * TAN, a fixed demo password, ...) that never belong in the production stepData contract
 * (docs/05-api.md #2). ToolControllerSupport lifts this key generically into the response's
 * `demo` object - handlers attach whatever demo values they have via [demoData] without any
 * per-field plumbing on the orchestrator side.
 */
const val DEMO_DATA_KEY = "demo"

/** `data = mapOf("missingFields" to listOf("tan"), demoData("tan" to issued.plainTan))` */
fun demoData(vararg values: Pair<String, Any?>): Pair<String, Map<String, Any?>> = DEMO_DATA_KEY to mapOf(*values)

/**
 * The one email address this demo ever confirms (enroll-email prefills it; both lookup-based
 * login tools prefill it too since they need it to resolve the very account enroll-email just
 * created). Shared here rather than duplicated per module because, unlike the TAN/password demo
 * values, it must be the SAME literal across auth_email/auth_sms/auth_password for a lookup to
 * actually find the account a tester just registered.
 */
const val DEMO_EMAIL = "max.mustermann@example.com"

/**
 * The module-boundary contract (docs/03-tool-architektur.md #2). Only this leaves a module -
 * never the internal FlowOutcome/State a module may use to structure itself.
 */
sealed interface ToolOutcome {

    /** Tool keeps running; [data] is client-facing and passed through as stepData unchanged. */
    data class InProgress(
        val nextStep: String,
        val data: Map<String, Any?>? = null
    ) : ToolOutcome

    /** Attempt failed; retry handling lives in the orchestrator (docs/04-orchestrierung.md). */
    data class Failed(val reason: String) : ToolOutcome

    /**
     * Tool finished. The variant matches the tool's category from the catalog and dictates
     * what the orchestrator does with it.
     */
    sealed interface Completed : ToolOutcome {
        /** amr value(s) this run proved, for AuthContext.currentAmr. */
        val amr: List<String>
        /** Level this run itself achieved, if the tool can determine it. */
        val achievedAcr: String?
        /** Factor kinds actually proven this run; subset of ToolDescriptor.factorTypes. */
        val factorTypes: Set<FactorType>

        data class Identified(
            val personId: Long,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** Method-specific verification evidence; passed through unchanged into account.identifications[].details. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed

        data class Enrolled(
            val enrollmentRef: EnrollmentRef,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** Method-specific delivery evidence; passed through unchanged into authenticationMethods[].details. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed

        data class Authenticated(
            override val amr: List<String>,
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * Set ONLY by lookup-based AUTH tools (auth-*-lookup), which resolve the account
             * themselves from a submitted email - ordinary device-bound auth-* tools leave this
             * null because the orchestrator already knows the account from the channel/process.
             */
            val accountId: Long? = null
        ) : Completed
    }
}
