package com.example.dpop.tool_spi

/**
 * The result of one tool step. This is the only thing a tool hands back across the module
 * boundary - any internal state a tool uses to structure itself stays inside its own module.
 */
sealed interface ToolOutcome {

    /** The tool is still running and expects another call. */
    data class InProgress(
        /**
         * What the client must do next, as this tool's own step vocabulary (e.g. `"code"`,
         * `"waitForApp"`). Surfaces as `next.step`; the FIRST one a fresh session reports must
         * equal the tool's declared [ToolDescriptor.startStep].
         */
        val nextStep: String,
        /**
         * What this step needs the client to see, as a declared shape ([StepData]). Passed through
         * as `stepData` unchanged - the orchestrator still never reads into it, it only carries it.
         *
         * Declared rather than a free map: the shape is the interesting half of the answer, and a
         * map left it out of the contract entirely. A tool that says nothing beyond "these inputs
         * are missing" uses the shared [MissingFields]; anything of its own it declares in its own
         * module (see [StepDataTypes]).
         */
        val stepData: StepData? = null,

        /**
         * Demo-only values this step wants to show (a plaintext TAN, a prefilled address).
         *
         * Its own field rather than a key inside the step data: it is not part of the production
         * contract, and only `DemoDisclosure` decides whether it reaches a client at all.
         */
        val demo: Map<String, Any?>? = null
    ) : ToolOutcome

    /**
     * The attempt failed; [reason] is a human-readable message for the client.
     *
     * The two id fields name WHO the failed attempt was against, so the orchestrator can charge
     * the right brute-force counter. They exist because a tool that resolves its own subject is
     * the only place that knows it: for a LOOKUP_AUTH tool the account is not on the channel
     * (that is the whole point of lookup login), and for an IDENT tool no account exists yet at
     * all. Exactly one is ever set, and only by the tools that resolve one - a IDENTIFIED_AUTH tool
     * leaves both null, because its caller already knows the account from the channel.
     *
     * Leaving them null is always safe for the response; it only means the attempt goes
     * uncounted, which is exactly the gap this field closes.
     */
    data class Failed(
        val reason: String,
        /** Set by a [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] tool that resolved an account before failing. */
        val attemptedAccountId: Long? = null,
        /** Set by an [IDENTIFICATION][MethodRole.IDENTIFICATION] tool that resolved a person before failing. */
        val attemptedPersonId: Long? = null
    ) : ToolOutcome

    /**
     * The tool finished successfully. The concrete variant matches the tool's [ToolDescriptor.role]
     * and determines what the caller does with the result.
     */
    sealed interface Completed : ToolOutcome {
        /** The amr value(s) this run proved. */
        val amr: List<String>
        /** The level this run itself achieved, if the tool can determine it. */
        val achievedAcr: AcrLevel?
        /** The factor kinds actually proven this run; a subset of [ToolDescriptor.factorTypes]. */
        val factorTypes: Set<FactorType>

        /**
         * An [IDENTIFICATION][MethodRole.IDENTIFICATION] tool established who the subject is.
         * The person reference is carried as a [claims] entry, never as a separate field
         * (docs/ideen/account-attribute-und-trust-vereinheitlichen.md, Paket 5).
         *
         * At most ONE `PERSON_ID` claim, possibly none: a procedure that attests what it read
         * without resolving anyone - `ident-eid`, whose card carries no person reference at all -
         * legitimately reports none, and the central resolution then decides whether those
         * attributes match an existing account or produce an Interessent (ADR-10). Two different
         * person references in one run stay a contract error, enforced here at construction.
         */
        data class Identified(
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * The identifying attributes this run asserted, with their provenance - the typed
             * counterpart to [auditDetails]. Subset of the descriptor's [ToolDescriptor.claims].
             */
            val claims: List<Claim> = emptyList(),
            /** Method-specific verification evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed {
            /** The `PERSON_ID` claim's value, parsed - `null` when this run resolved nobody. */
            val personId: Long?
                get() = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }?.value?.trim()?.toLong()

            init {
                val personIdClaims = claims.count { it.attributeType == AttributeType.PERSON_ID }
                check(personIdClaims <= 1) {
                    "Completed.Identified allows at most one PERSON_ID claim, got $personIdClaims"
                }
                claims.forEach { it.validateValue() }
            }
        }

        /** An [ENROLLMENT][MethodRole.ENROLLMENT] tool created a durable credential. */
        data class Enrolled(
            /**
             * Points at the credential row the tool's own module just wrote - the only handle
             * anyone outside that module ever holds on it, used to authenticate against it later
             * and to delete it on account deletion ([com.example.dpop.tool_api.EnrollmentCleanup]).
             */
            val enrollmentRef: EnrollmentRef,
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * The identifying attributes this enrollment asserted about its subject, with their
             * provenance - the typed counterpart to [auditDetails]. Subset of the descriptor's
             * [ToolDescriptor.claims].
             */
            val claims: List<Claim> = emptyList(),
            /** Method-specific delivery evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed

        /**
         * An [ATTESTATION][MethodRole.ATTESTATION] tool proved the subject controls an attribute:
         * claims, but no credential and no identity resolution. Between [Identified] (claims plus
         * resolution) and [Enrolled] (claims plus credential).
         *
         * [amr] stays empty and is not overridable: a confirmed address is no authentication
         * proof and must not raise this channel's ACR/AMR balance. Reporting one as an [Enrolled]
         * would do exactly that, which is why attestation has a variant of its own.
         */
        data class Attested(
            /**
             * What the subject just proved control of, with its provenance. Never empty - an
             * attestation asserting nothing is a contract error, enforced below.
             */
            val claims: List<Claim>,
            override val achievedAcr: AcrLevel? = null,
            /** Method-specific verification evidence, passed through unchanged for auditing. */
            val auditDetails: Map<String, Any?>? = null
        ) : Completed {
            override val amr: List<String> = emptyList()
            override val factorTypes: Set<FactorType> = emptySet()

            init {
                check(claims.isNotEmpty()) { "Completed.Attested without a claim attests nothing" }
                claims.forEach { it.validateValue() }
            }
        }

        /** A [IDENTIFIED_AUTH][MethodRole.IDENTIFIED_AUTH] or [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] tool succeeded. */
        data class Authenticated(
            override val amr: List<String>,
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
            /**
             * Set only by a [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] tool, which resolves the
             * account itself from a submitted identifier. Left `null` by a
             * [IDENTIFIED_AUTH][MethodRole.IDENTIFIED_AUTH] tool, whose caller already knows the account.
             */
            val accountId: Long? = null
        ) : Completed

        /**
         * A [PEER_APPROVAL][MethodRole.PEER_APPROVAL] tool approved a pending request from another
         * channel. Declining is not a separate variant - it is an ordinary [Failed], since the
         * existing retry/abandon handling already covers it.
         */
        data class Approved(
            override val amr: List<String> = emptyList(),
            override val achievedAcr: AcrLevel? = null,
            override val factorTypes: Set<FactorType> = emptySet(),
        ) : Completed
    }
}
