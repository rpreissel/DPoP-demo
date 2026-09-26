package com.example.dpop.tool_spi

import com.example.dpop.texts.Text

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
     * WHO the attempt was against decides which brute-force counter the orchestrator charges, and
     * for some tools only the tool knows: a LOOKUP_AUTH tool resolves its account from the input
     * (it is not on the channel - that is the point of lookup login), an IDENT tool resolves a
     * person before any account exists. So there is one variant per kind of subject, each naming it
     * as a REQUIRED field: a handler cannot fail without saying whom the attempt was against, and
     * "nobody" is an explicit `null`, not a forgotten default (review 2026-09, S-6: `ident-kvnr`
     * answered a foreign KVNR without its person, and the attempt went uncounted).
     *
     * Which variant a tool may use follows from its [ToolDescriptor.role] ([fits]); the orchestrator
     * refuses any other before it charges anything.
     */
    sealed interface Failed : ToolOutcome {
        val reason: Text

        /** An [IDENTIFIED_AUTH][MethodRole.IDENTIFIED_AUTH] attempt - against the account the channel already knows. */
        data class IdentifiedAuth(override val reason: Text) : Failed

        /** A [LOOKUP_AUTH][MethodRole.LOOKUP_AUTH] attempt - against the account the input resolved, `null` if it resolved none. */
        data class LookupAuth(override val reason: Text, val attemptedAccountId: Long?) : Failed

        /**
         * An [IDENTIFICATION][MethodRole.IDENTIFICATION] or [CORRELATION][MethodRole.CORRELATION]
         * attempt - against the person the input resolved, `null` if it resolved none.
         */
        data class Identification(override val reason: Text, val attemptedPersonId: String?) : Failed

        /**
         * An [ENROLLMENT][MethodRole.ENROLLMENT], [ATTESTATION][MethodRole.ATTESTATION] or
         * [PEER_APPROVAL][MethodRole.PEER_APPROVAL] attempt - nothing secret of an existing account
         * was guessed (the user chooses the credential, or the code went to the address being
         * claimed), so no counter applies; the ToolSession's own limits bound it.
         */
        data class NothingGuessed(override val reason: Text) : Failed

        fun fits(role: MethodRole): Boolean = when (this) {
            is IdentifiedAuth -> role == MethodRole.IDENTIFIED_AUTH
            is LookupAuth -> role == MethodRole.LOOKUP_AUTH
            is Identification -> role == MethodRole.IDENTIFICATION || role == MethodRole.CORRELATION
            is NothingGuessed -> role == MethodRole.ENROLLMENT || role == MethodRole.ATTESTATION || role == MethodRole.PEER_APPROVAL
        }
    }

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
            val personId: String?
                get() = claims.firstOrNull { it.attributeType == AttributeType.PERSON_ID }?.value?.trim()

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
             * provenance. Subset of the descriptor's [ToolDescriptor.claims].
             */
            val claims: List<Claim> = emptyList(),
            /**
             * What the owning module itself needs to read back about THIS instance later - through
             * its own [ToolDescriptor.keyBinding] or [ToolDescriptor.instanceDisclosure], never
             * through anyone else. Stored with the method and gone with it, so it is no audit
             * evidence (ADR-39): how the method was added is recorded by the account module's
             * `METHOD_ADDED` event. Nothing else belongs here.
             */
            val instanceDetails: Map<String, Any?> = emptyMap(),
            /** User-chosen display name, meaningful only for multi-instance methods. */
            val label: String? = null
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
            override val achievedAcr: AcrLevel? = null
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
