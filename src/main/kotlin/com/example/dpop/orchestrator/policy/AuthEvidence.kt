package com.example.dpop.orchestrator.policy

import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.orchestrator.session.AmrSource
import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolDescriptor

/**
 * Nominal wrapper for an auth method identifier ("sms", "password", "eid", ...). Deliberately
 * NOT an enum: the set of methods is open - every module declares its own via its own
 * `Descriptor` object, aggregated at runtime by `ToolHandlerRegistry` (docs/03-tool-architektur.md
 * #1). A `value class` still buys real nominal typing over a plain String at zero runtime cost -
 * it's what stops a method name and an acr level (see [AcrLevel]) from being silently
 * interchangeable the way two `String` fields would be.
 */
@JvmInline
value class MethodName(val value: String) {
    override fun toString(): String = value
}

/**
 * Which trust question a [MethodEvidence] entry answers - the split NIST 800-63 draws between
 * IAL (Identity Assurance: "who is this?", established once by an IDENTIFICATION-role tool like
 * `ident-fsc`) and AAL (Authenticator Assurance: "is this the same person who registered this
 * credential, proven again right now?", established by ENROLLMENT/AUTH-role tools). Not tracked
 * before this: `resolveAcr` used to fold both into one undifferentiated max, which let a stale
 * identification's loa or factor type silently participate in an MFA bump it was never meant to
 * (see [DefaultAuthPolicy] class doc) - an identification is a one-time historical event, never a
 * factor re-presented at the moment of authentication, so it must never count toward "how hard is
 * THIS login to break".
 */
enum class EvidenceAxis {
    IDENTITY,
    AUTHENTICATOR
}

/** [EvidenceAxis.IDENTITY] for an IDENTIFICATION-role tool, [EvidenceAxis.AUTHENTICATOR] for every other role that produces evidence (ENROLLMENT, AUTH). */
fun ToolDescriptor.evidenceAxis(): EvidenceAxis =
    if (role.category == ToolCategory.IDENT) EvidenceAxis.IDENTITY else EvidenceAxis.AUTHENTICATOR

/**
 * One proven method's own evidence. Deliberately ONE record per method rather than several
 * parallel Method->X maps: nothing then lets a method appear in one collection but not another,
 * and a future per-method field (e.g. an expiry timestamp) is one more property here, not a
 * fourth map that has to be kept in lockstep with the others.
 */
data class MethodEvidence(
    val method: MethodName,
    /** This method's own loa - the base [AuthPolicy.resolveAcr] prices from. */
    val loa: AcrLevel,
    /**
     * This method's own ceiling for an MFA COMBINATION involving it (docs/06-ablaeufe.md #1) -
     * an orchestrator method's own account enrollment record; for a method with no such record
     * (any method Keycloak reported natively, which this system never enrolls - docs/05-api.md
     * Abschnitt 3), the caller that assembled this evidence supplies one directly
     * (conservatively, its own [loa], unless it knows better). Null contributes nothing to the
     * cap, same as an account with no matching enrollment at all - never a special case to
     * detect, just what "no entry" already means.
     */
    val enrolledUnderAcr: AcrLevel? = null,
    /** This method's own contributed factor kinds - what [AuthEvidence.factorTypes] unions over. */
    val factorTypes: Set<FactorType> = emptySet(),
    /**
     * [AmrSource.ORCHESTRATOR] or [AmrSource.KEYCLOAK] - purely a passthrough field, never read by
     * [AuthPolicy] itself (the doc on [AuthEvidence] below still holds: pricing is source-
     * agnostic). Carried here, not only on the session-layer `AmrRecord`, so it survives a
     * `RestoreData` round-trip - without it, a method a completed ORCHESTRATOR tool proved before
     * a step-up would come back out of `RestoreData` looking like a mere `kc` self-report, and
     * become silently downgradable by a later native re-report for the same method (defeating the
     * "kc never downgrades orchestrator" invariant `AuthEvidence.addAmr`, the session-layer
     * entity, actually enforces from this field).
     */
    val source: String,
    /**
     * A stable identifier for whatever specifically produced this proof - a completed
     * orchestrator tool's own `toolId`, Keycloak's own authenticator/execution id for a natively
     * reported method (docs/05-api.md Abschnitt 3), or `"simulation"` for a
     * hypothetical candidate `AuthPolicy` itself projects (never real evidence, nothing "produced"
     * it) - always a real, deliberate value, never left blank: every caller that builds a
     * [MethodEvidence] knows exactly which of these three cases it is in.
     */
    val amrSourceId: String,
    /** Which trust question this entry answers - see [EvidenceAxis]. Defaults to [EvidenceAxis.AUTHENTICATOR], the common case for every AUTH/ENROLL tool; only an IDENTIFICATION-role tool's outcome sets [EvidenceAxis.IDENTITY] (`JourneyService.recordToolCompletion`, via [evidenceAxis]). */
    val axis: EvidenceAxis = EvidenceAxis.AUTHENTICATOR,
)

/**
 * What this session has already proven, read from the AuthContext (docs/04-orchestrierung.md #2).
 *
 * Deliberately source-agnostic: whether a method in [factors] was proven by a completed
 * orchestrator tool or reported by Keycloak's own native authenticator (docs/05-api.md
 * Abschnitt 3) makes no difference here or to [AuthPolicy] - the caller that
 * assembles this already resolved each method's own loa and contributed factor types before
 * handing it over, so pricing never needs to look anything up itself.
 */
data class AuthEvidence(
    val factors: List<MethodEvidence>,
) {
    /** Every method proven so far - derived, never a separately stored collection to fall out of sync. */
    val amr: List<MethodName> get() = factors.map { it.method }

    /**
     * The union of every proven method's own [MethodEvidence.factorTypes] - derived rather than a
     * sibling collection, for the same reason [amr] is: nothing could then let it name a factor
     * kind whose contributing method isn't even in [factors].
     */
    val factorTypes: Set<FactorType> get() = factors.flatMap { it.factorTypes }.toSet()

    fun loaFor(method: MethodName): AcrLevel? = factors.find { it.method == method }?.loa

    companion object {
        /**
         * Builds evidence from the flat shape a caller typically resolves things in - one amr
         * list plus method-keyed maps (what `AuthContext` persists, and what most callers still
         * assemble their own inputs as), plus one [factorTypes] set for this whole call. This is
         * the one seam where those get zipped into [factors]; everything past construction only
         * ever sees the single per-method list. [factorTypes] is attached to EVERY method built
         * from [amr] in this call: a caller invoking [from] once per proven method (the common
         * case) gets exact per-method attribution for free; a caller reporting several methods
         * from one coarser source (e.g. Keycloak's own "Selbstauskunft", docs/05-api.md
         * Abschnitt 3) gets the same flat union as before this was per-method at all
         * - either way [AuthEvidence.factorTypes]'s derived union comes out identical to just
         * passing [factorTypes] through directly.
         */
        fun from(
            amr: List<String>,
            factorTypes: Set<FactorType>,
            methodLoa: Map<String, String> = emptyMap(),
            enrolledUnderAcr: Map<String, String> = emptyMap(),
            source: Map<String, String> = emptyMap(),
            amrSourceId: Map<String, String> = emptyMap(),
            axis: Map<String, EvidenceAxis> = emptyMap(),
        ): AuthEvidence = AuthEvidence(
            amr.distinct().map { m ->
                MethodEvidence(
                    MethodName(m),
                    methodLoa[m]?.let(::AcrLevel) ?: AcrLevel("none"),
                    enrolledUnderAcr[m]?.let(::AcrLevel),
                    factorTypes,
                    // Defaults to the stronger claim when a caller (mostly test fixtures) has no
                    // opinion - never read by AuthPolicy itself either way.
                    source[m] ?: AmrSource.ORCHESTRATOR,
                    // Falls back to the method name itself when the caller has no real source id
                    // on hand (most callers reconstructing evidence from already-persisted data
                    // don't carry one through this flat factory) - still a real, deterministic
                    // value, never a fabricated placeholder.
                    amrSourceId[m] ?: m,
                    // Defaults to AUTHENTICATOR, the common case - a caller building evidence for
                    // an IDENTIFICATION-role method (e.g. "fsc") must say so explicitly.
                    axis[m] ?: EvidenceAxis.AUTHENTICATOR,
                )
            },
        )
    }
}
