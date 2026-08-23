package com.example.dpop.tool_spi

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
            override val factorTypes: Set<FactorType> = emptySet()
        ) : Completed
    }
}
