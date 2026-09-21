package com.example.dpop.tool_spi

import java.time.LocalDate

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
    /**
     * Card-bound pseudonym from the eID read - this demo's stand-in for the real "Restricted
     * Identifier": fixed per physical card, it changes when a new card is issued, but is never
     * assigned to a different person. Hence the natural RECOGNITION anchor for an eid-identified
     * Interessent: a replaceable local account anchor (ADR-19), not a master-data attribute -
     * ext_stammdaten never stores it.
     */
    EID_RESTRICTED_ID("restricted_id"),
    /** Family name. Master-data field for a bound account, attested history in the claim log. */
    NAME("name"),
    /** Given name(s). Master-data field, same rule as [NAME]. */
    VORNAME("vorname"),
    /** ISO date, e.g. `1970-01-01`. Master-data field: delegated, never projected (ADR notes in
     *  docs/ideen/claims-modell-und-vertrauensanker.md). */
    GEBURTSDATUM("geburtsdatum"),
    /**
     * Street name, first of the four address fields ([STRASSE], [HAUSNUMMER], [PLZ], [ORT]) - the
     * same German wire names the eID card and `ext_stammdaten.person` use, so a card read's claim
     * values map 1:1 onto register columns. All four are master-data fields like [GEBURTSDATUM]:
     * register-owned for bound accounts, claim-log rows are attestation history.
     */
    STRASSE("strasse"),
    /** House number, kept apart from [STRASSE] because the eID card delivers it as its own field. */
    HAUSNUMMER("hausnummer"),
    /** Postal code. */
    PLZ("plz"),
    /** City/town. */
    ORT("ort"),
    /**
     * E-mail address. Unlike every other attribute here, a LOCAL_ANCHOR the account owns rather
     * than a master-data field: `confirm-email` establishes it, three lookup tools resolve an
     * account through it, and a retraction deletes its anchor row outright (ADR-12).
     */
    EMAIL("email"),
    /** Mobile number, established by an `enroll-sms` run - the address a TAN is delivered to. */
    PHONE_NUMBER("phone_number"),

    /**
     * "This account holds a password credential" - established by an `enroll-password` run and
     * retracted with it, like any other METHOD_MODULE attribute.
     *
     * The one member of this taxonomy that is not a fact about the PERSON but about the account's
     * credentials, and it is here on purpose rather than as a second mechanism beside the claim
     * log: a dependency between methods is then simply a `requires`, and the existing retraction
     * machinery makes it fall away by itself when the password goes. Nothing requires it at the
     * moment - it is the vocabulary such a dependency would be written in, kept because the
     * alternative (inventing a second dependency mechanism when one is next needed) is worse.
     *
     * Carries no useful value - it is the assertion itself that matters - so its claim is written
     * with a constant marker rather than a secret or a digest of one.
     */
    PASSWORD_EXISTS("password_exists");

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
value class ClaimSource(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The master-data backend (ext_stammdaten) - strongest trust level. */
        val EXT_STAMMDATEN = ClaimSource("ext_stammdaten")

        /** A value the user entered with nothing backing it. */
        val SELF_REPORTED = ClaimSource("self-reported")

        /**
         * Demo-only startup seed data (`demo_seed.KcDemoAccountSeeder`), rank [TrustLevel.PROVEN]
         * (docs/ideen/account-attribute-und-trust-vereinheitlichen.md, Paket 6) - explicitly
         * named so it can never be mistaken for stammdaten authority or real session evidence.
         * Not [of] a [ToolId]: no tool run ever produced this value, and no tool descriptor
         * declares it, so it must not be usable outside the one seeder that legitimately writes it.
         */
        val DEMO_BOOTSTRAP = ClaimSource("demo-bootstrap")

        /** A claim established by a concrete tool run, e.g. an eID procedure. */
        fun of(toolId: ToolId): ClaimSource = ClaimSource(toolId.value)
    }
}

/**
 * The three classes of trust a [ClaimSource] can carry, ordered by precedence (docs/ideen/
 * claims-modell-und-vertrauensanker.md: trust level first, recency only as a tie-breaker
 * WITHIN one level). Higher [rank] outranks lower.
 */
enum class TrustLevel(val rank: Int) {
    /** Backed by the master-data backend, e.g. ext_stammdaten. */
    STAMMDATEN(3),
    /** Proven by a tool run, e.g. an eID procedure or a confirmed email-code exchange. */
    PROVEN(2),
    /** The user vouched for it, nothing else. */
    SELF_REPORTED(1)
}

/** The [TrustLevel] this [ClaimSource] belongs to. */
val ClaimSource.trustLevel: TrustLevel
    get() = when (this) {
        ClaimSource.EXT_STAMMDATEN -> TrustLevel.STAMMDATEN
        ClaimSource.SELF_REPORTED -> TrustLevel.SELF_REPORTED
        else -> TrustLevel.PROVEN
    }

/** The only value a [AttributeType.PASSWORD_EXISTS] claim ever carries - the claim IS the statement. */
const val PASSWORD_EXISTS_MARKER = "true"

/**
 * One attribute value a completed tool run asserts about its subject, with its provenance: WHO
 * established it ([source]) and at what assurance ([establishedAcr]). The typed claims-
 * model counterpart to the untyped `auditDetails` blob - a subset of the declaring descriptor's
 * [ToolDescriptor.claims], at most one per [AttributeType] (docs/ideen/
 * claims-modell-und-vertrauensanker.md).
 */
data class Claim(
    /** Which attribute is being asserted. */
    val attributeType: AttributeType,
    /** The asserted value, unnormalized - normalization for anchor lookups happens in `account`. */
    val value: String,
    /** Who vouches for [value] - decides the [TrustLevel] via [ClaimSource.trustLevel]. */
    val source: ClaimSource,
    /**
     * The level the session had proven when this claim was established (ADR-5). `null` when the
     * asserting run could not determine one - the claim still counts, it just carries no
     * assurance of its own and can never raise a level by itself.
     */
    val establishedAcr: AcrLevel? = null
)

/**
 * The invariants every [Claim] must satisfy whatever asserted it, checked before resolution or
 * persistence: a non-blank [Claim.value], a positive integer for `PERSON_ID`, and an ISO date for
 * `GEBURTSDATUM`. A violation is descriptor/handler drift, so it throws rather than returning a
 * verdict - see [assertClaimsCovered], which calls this for every reported claim.
 */
fun Claim.validateValue() {
    check(value.isNotBlank()) {
        "${attributeType.wireName} claim must not be blank"
    }
    when (attributeType) {
        AttributeType.PERSON_ID -> check(value.trim().toLongOrNull()?.let { it > 0 } == true) {
            "person_id claim must be a positive integer"
        }
        AttributeType.GEBURTSDATUM -> check(runCatching { LocalDate.parse(value.trim()) }.isSuccess) {
            "geburtsdatum claim must be an ISO date"
        }
        else -> Unit
    }
}

/**
 * What an account must already have for a tool to be offered at all: [attributeType]
 * established at no less than [minTrustLevel], checked against the consolidated value
 * including retractions (ADR-12). The mirror direction of [ToolDescriptor.claims] -
 * colloquially, "confirmed email" is `ClaimRequirement(EMAIL, PROVEN)`.
 */
data class ClaimRequirement(
    val attributeType: AttributeType,
    val minTrustLevel: TrustLevel
)

/**
 * What [ToolDescriptor.claims] declares: one [AttributeType] together with the [ClaimSource]
 * a successful run asserts it with. The OFFER side of the claims vocabulary (mirroring
 * [FactorType]'s two-sided contract): consumers can ask "what can this tool assert, on whose
 * authority?" without any run having happened. What is constant per tool lives here; what
 * varies per run - the value and its [Claim.establishedAcr] - stays on the [Claim]. The
 * [TrustLevel] is deliberately NOT declared: it is derived via [ClaimSource.trustLevel], and
 * the source-to-level precedence is global policy (ADR-11), not per-tool knowledge.
 */
data class ClaimDeclaration(
    val attributeType: AttributeType,
    val source: ClaimSource
)

/**
 * Fail-fast contract check between a descriptor's declared [ToolDescriptor.claims] and the
 * [Claim]s one completed run actually reported: every reported claim must be declared for the
 * same [AttributeType] with the SAME [ClaimSource], and at most one claim per [AttributeType]
 * (docs/ideen/claims-modell-und-vertrauensanker.md: a `Claim` set is a snapshot, not a log -
 * two values for the same attribute in one report is descriptor/handler drift, same as an
 * undeclared attribute). Descriptor/handler drift is a programming error, not a runtime
 * condition - it crashes the adopting transaction instead of silently logging an assertion the
 * catalog never promised.
 */
fun assertClaimsCovered(descriptor: ToolDescriptor, claims: List<Claim>) {
    val declared = descriptor.claims.associateBy { it.attributeType }
    val seen = mutableSetOf<AttributeType>()
    claims.forEach { claim ->
        claim.validateValue()
        val declaration = checkNotNull(declared[claim.attributeType]) {
            "${descriptor.toolId} reported a ${claim.attributeType.wireName} claim but declares none"
        }
        check(declaration.source == claim.source) {
            "${descriptor.toolId} reported ${claim.attributeType.wireName} with source ${claim.source}, but declares ${declaration.source}"
        }
        check(seen.add(claim.attributeType)) {
            "${descriptor.toolId} reported more than one claim for ${claim.attributeType.wireName}"
        }
    }
}
