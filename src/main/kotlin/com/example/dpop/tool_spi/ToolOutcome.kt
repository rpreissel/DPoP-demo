package com.example.dpop.tool_spi

/**
 * The result of one tool step. This is the only thing a tool hands back across the module
 * boundary - any internal state a tool uses to structure itself stays inside its own module.
 */
sealed interface ToolOutcome {

    /** The tool is still running. [data] is client-facing and passed through as `stepData` unchanged. */
    data class InProgress(
        val nextStep: String,
        val data: Map<String, Any?>? = null
    ) : ToolOutcome

    /** The attempt failed; [reason] is a human-readable message for the client. */
    data class Failed(val reason: String) : ToolOutcome

    /**
     * The tool finished successfully. The concrete variant matches the tool's [ToolDescriptor.role]
     * and determines what the caller does with the result.
     */
    sealed interface Completed : ToolOutcome {
        /** The amr value(s) this run proved. */
        val amr: List<String>
        /** The level this run itself achieved, if the tool can determine it. */
        val achievedAcr: String?
        /** The factor kinds actually proven this run; a subset of [ToolDescriptor.factorTypes]. */
        val factorTypes: Set<FactorType>

        /** An [IDENTIFICATION][MethodRole.IDENTIFICATION] tool resolved [personId]. */
        data class Identified(
            val personId: Long,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** Method-specific verification evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed

        /** An [ENROLLMENT][MethodRole.ENROLLMENT] tool created [enrollmentRef]. */
        data class Enrolled(
            val enrollmentRef: EnrollmentRef,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /** Method-specific delivery evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed

        /** A [DEVICE_AUTH][MethodRole.DEVICE_AUTH] or [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] tool succeeded. */
        data class Authenticated(
            override val amr: List<String>,
            override val achievedAcr: String? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * Set only by a [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] tool, which resolves the
             * account itself from a submitted identifier. Left `null` by a
             * [DEVICE_AUTH][MethodRole.DEVICE_AUTH] tool, whose caller already knows the account.
             */
            val accountId: Long? = null
        ) : Completed
    }
}
