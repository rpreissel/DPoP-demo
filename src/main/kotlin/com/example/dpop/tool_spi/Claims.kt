package com.example.dpop.tool_spi

/**
 * A kind of identifying attribute a tool can assert about its subject. Closed taxonomy on
 * purpose, mirroring [FactorType]: it appears on BOTH sides of the tool contract
 * ([ToolDescriptor.claims] declares what a tool may assert, the [Claim]s on a completed
 * [ToolOutcome] carry what one run actually asserted), so typo safety at the contract weighs
 * more than schema generality - a plain String would let `"emial"` compile. Extending the
 * taxonomy is a deliberate act (new enum case + descriptor updates), not a runtime config change.
 */
enum class AttributeType(val wireName: String) {
    /** The person row this account belongs to, as the master-data backend's (ext_stammdaten) PK. */
    PERSON_ID("person_id"),
    /** Krankenversichertennummer - the anchor a person is resolved by in the master data. */
    KVNR("kvnr"),
    NAME("name"),
    VORNAME("vorname"),
    /** ISO date, e.g. `1970-01-01`. Master-data field: delegated, never projected (ADR notes in
     *  docs/ideen/claims-modell-und-vertrauensanker.md). */
    GEBURTSDATUM("geburtsdatum"),
    EMAIL("email"),
    PHONE_NUMBER("phone_number");

    companion object {
        /** Reverse of [wireName] - the JPA persistence converter's only caller (C2, account.internal.AttributeTypeConverter). */
        fun fromWireName(wireName: String): AttributeType = entries.first { it.wireName == wireName }
    }
}

/**
 * Nominal wrapper for the SOURCE a [Claim] was established by: the master-data backend
 * ([EXT_STAMMDATEN]), a concrete tool run ([of] a [ToolId] - e.g. an eID procedure), or nobody
 * but the user ([SELF_REPORTED]). Same value-class discipline as [ToolId]: the three kinds must
 * not be silently interchangeable the way raw Strings would be.
 */
@JvmInline
value class TrustAnchor(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The master-data backend (ext_stammdaten) - strongest anchor class. */
        val EXT_STAMMDATEN = TrustAnchor("ext_stammdaten")

        /** A value the user entered with nothing backing it. */
        val SELF_REPORTED = TrustAnchor("self-reported")

        /** A claim established by a concrete tool run, e.g. an eID procedure. */
        fun of(toolId: ToolId): TrustAnchor = TrustAnchor(toolId.value)
    }
}

/**
 * The three classes of trust a [TrustAnchor] can carry, ordered by precedence (docs/ideen/
 * claims-modell-und-vertrauensanker.md: anchor class first, recency only as a tie-breaker
 * WITHIN one class). Higher [rank] outranks lower.
 */
enum class AnchorClass(val rank: Int) {
    /** Backed by the master-data backend, e.g. ext_stammdaten. */
    STAMMDATEN(3),
    /** Proven by a tool run, e.g. an eID procedure or a confirmed email-code exchange. */
    PROVEN(2),
    /** The user vouched for it, nothing else. */
    SELF_REPORTED(1)
}

/** The [AnchorClass] a given [TrustAnchor] belongs to. */
fun anchorClassOf(trustAnchor: TrustAnchor): AnchorClass = when (trustAnchor) {
    TrustAnchor.EXT_STAMMDATEN -> AnchorClass.STAMMDATEN
    TrustAnchor.SELF_REPORTED -> AnchorClass.SELF_REPORTED
    else -> AnchorClass.PROVEN
}

/**
 * One attribute value a completed tool run asserts about its subject, with its provenance: WHO
 * established it ([trustAnchor]) and at what assurance ([establishedLoa]). The typed claims-
 * model counterpart to the untyped `auditDetails` blob - a subset of the declaring descriptor's
 * [ToolDescriptor.claims], at most one per [AttributeType] (docs/ideen/
 * claims-modell-und-vertrauensanker.md).
 */
data class Claim(
    val attributeType: AttributeType,
    val value: String,
    val trustAnchor: TrustAnchor,
    val establishedLoa: AcrLevel? = null
)

/**
 * What an account must already have for a tool to be offered at all: [attributeType]
 * established at no less than [minAnchorClass], checked against the consolidated value
 * including retractions (ADR-12). The mirror direction of [ToolDescriptor.claims] -
 * colloquially, "confirmed email" is `ClaimRequirement(EMAIL, PROVEN)`.
 */
data class ClaimRequirement(
    val attributeType: AttributeType,
    val minAnchorClass: AnchorClass
)

/**
 * What [ToolDescriptor.claims] declares: one [AttributeType] together with the [TrustAnchor]
 * a successful run asserts it with. The OFFER side of the claims vocabulary (mirroring
 * [FactorType]'s two-sided contract): consumers can ask "what can this tool assert, on whose
 * authority?" without any run having happened. What is constant per tool lives here; what
 * varies per run - the value and its [Claim.establishedLoa] - stays on the [Claim]. The
 * [AnchorClass] is deliberately NOT declared: it is derived via [anchorClassOf], and the
 * anchor-to-class precedence is global policy (ADR-11), not per-tool knowledge.
 */
data class ClaimDeclaration(
    val attributeType: AttributeType,
    val trustAnchor: TrustAnchor
)

/**
 * Fail-fast contract check between a descriptor's declared [ToolDescriptor.claims] and the
 * [Claim]s one completed run actually reported: every reported claim must be declared for the
 * same [AttributeType] with the SAME [TrustAnchor]. Descriptor/handler drift is a programming
 * error, not a runtime condition - it crashes the adopting transaction instead of silently
 * logging an assertion the catalog never promised.
 */
fun assertClaimsCovered(descriptor: ToolDescriptor, claims: List<Claim>) {
    val declared = descriptor.claims.associateBy { it.attributeType }
    claims.forEach { claim ->
        val declaration = checkNotNull(declared[claim.attributeType]) {
            "${descriptor.toolId} reported a ${claim.attributeType.wireName} claim but declares none"
        }
        check(declaration.trustAnchor == claim.trustAnchor) {
            "${descriptor.toolId} reported ${claim.attributeType.wireName} with anchor ${claim.trustAnchor}, but declares ${declaration.trustAnchor}"
        }
    }
}
