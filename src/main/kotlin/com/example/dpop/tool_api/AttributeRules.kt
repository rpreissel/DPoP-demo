package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType

/**
 * The small, explicit rules that make some [AttributeType]s anchors - values accounts and
 * identities are looked up BY - and others mere projection attributes (docs/ideen/
 * claims-modell-und-vertrauensanker.md, "Zwei Sorten Auflösung"; docs/ideen/account-attribute-
 * und-trust-vereinheitlichen.md). Replaces the former standalone `AnchorType` taxonomy:
 * [AttributeType] is the only attribute key, also for anchor operations - these extensions are
 * its anchor-specific behaviour, not a second parallel type. `tool_spi` stays free of this
 * persistence-adjacent knowledge on purpose; it lives here in `tool_api` instead.
 */

/**
 * Who owns the current value of an attribute - the question [anchorBindingStrength] below can
 * only answer negatively. Without it, "not a local anchor" is a single absence covering two
 * unrelated situations: `NAME` has its truth in the master data, `PHONE_NUMBER` in a method
 * module's own credential row. Both would read as `null` and look like the same rule.
 *
 * Successor to the removed `ConsolidationStrategy.ExternalLiveLookup` (docs/12-entscheidungen.md
 * ADR-14): that taxonomy's other case became redundant when the projection columns disappeared,
 * but this one did not, and an attribute's authority must stay a declared case rather than an
 * inference drawn from a missing anchor rank.
 */
enum class AttributeAuthority {
    /** Stored locally in `account.anchor`, which is also the uniqueness authority for it. */
    LOCAL_ANCHOR,

    /**
     * Owned by the master-data backend and read live through [PersonDirectory] whenever it is
     * needed - never projected into a local column, so it cannot go stale. Local
     * `account.attribute` rows for these types are claim history (what was asserted, by whom,
     * when), never the current truth.
     */
    EXT_STAMMDATEN,

    /**
     * Owned by the method module that enrolled it, in its own `<module>_enrollment` row (e.g.
     * `auth_sms.enrollment.phone_number`). The `account` module never resolves it.
     */
    METHOD_MODULE
}

/**
 * Where this attribute's current value lives. Exhaustive on purpose: a new [AttributeType]
 * does not compile until its authority is decided, which is the whole point - the decision is
 * not derivable from the type's name.
 *
 * Invariant, asserted in `AttributeRulesTest`: [LOCAL_ANCHOR][AttributeAuthority.LOCAL_ANCHOR]
 * holds exactly for the types with a non-null [anchorBindingStrength]. Both properties stay
 * separate because they answer different questions - "who owns it" vs. "how strongly does a
 * match on it bind an identity" - and only the first one has an answer for every type.
 */
val AttributeType.authority: AttributeAuthority
    get() = when (this) {
        AttributeType.PERSON_ID,
        AttributeType.EMAIL -> AttributeAuthority.LOCAL_ANCHOR
        AttributeType.KVNR,
        AttributeType.NAME,
        AttributeType.VORNAME,
        AttributeType.GEBURTSDATUM -> AttributeAuthority.EXT_STAMMDATEN
        AttributeType.PHONE_NUMBER -> AttributeAuthority.METHOD_MODULE
    }

/**
 * The assurance an anchor write must be backed by. Two levels, because the two writes are not the
 * same risk: [establish] is the first binding of a value to an account, [replace] re-points an
 * account that already resolves by some other value - and that is the attack, since the anchor is
 * the lookup authority every lookup-login resolves through.
 *
 * Deliberately separate from [AttributeType.allowsAnchorReplacement]: that one answers WHETHER a
 * replacement is permitted at all, this one WITH WHAT it has to be paid for. `PERSON_ID` says no
 * to the first question, so its [replace] level is never reached - it stands as the second line of
 * defence, not the first.
 */
data class AnchorAcrFloor(val establish: AcrLevel, val replace: AcrLevel)

/**
 * The floor for writing this type's anchor, or `null` if it is no anchor at all. Exhaustive like
 * its siblings: a new [AttributeType] does not compile until the decision is made.
 *
 * `EMAIL` establishes at loa1 on purpose - a registration has proven nothing yet, so demanding
 * loa2 there would make the first account impossible; replacing it costs loa2, because that write
 * is what would hand someone else's account to a new address.
 *
 * Invariant, asserted in `AttributeRulesTest`: non-null exactly for
 * [AttributeAuthority.LOCAL_ANCHOR] types - the same shape the other two rules are bound by.
 */
val AttributeType.anchorAcrFloor: AnchorAcrFloor?
    get() = when (this) {
        AttributeType.PERSON_ID -> AnchorAcrFloor(establish = AcrLevel.LOA2, replace = AcrLevel.LOA2)
        AttributeType.EMAIL -> AnchorAcrFloor(establish = AcrLevel.LOA1, replace = AcrLevel.LOA2)
        AttributeType.KVNR,
        AttributeType.NAME,
        AttributeType.VORNAME,
        AttributeType.GEBURTSDATUM,
        AttributeType.PHONE_NUMBER -> null
    }

/**
 * The binding strength an anchor match on this attribute type carries, or `null` if this
 * attribute is no anchor at all - see [authority] for what owns it instead. `PERSON_ID`
 * outranks the others (3) - it is the strongest possible identity match; `EMAIL` has rank 2.
 * KVNR is resolved live through PersonDirectory, not stored as a local account anchor. A new
 * anchor kind is a deliberate new case here, not a silently-added `if`.
 */
val AttributeType.anchorBindingStrength: Int?
    get() = when (this) {
        AttributeType.PERSON_ID -> 3
        AttributeType.EMAIL -> 2
        AttributeType.KVNR,
        AttributeType.NAME,
        AttributeType.VORNAME,
        AttributeType.GEBURTSDATUM,
        AttributeType.PHONE_NUMBER -> null
    }

/**
 * Canonical form of [value] for storage and lookup, applied identically on write
 * (`AccountService` stores the normalized form) and on read (`AccountDirectory.resolveByAnchor`),
 * so spellings agree regardless of who typed them. `PERSON_ID` normalizes to the canonical
 * decimal representation of its `Long` value; `EMAIL`/`KVNR` go through [Email]/[Kvnr] - an
 * invalid value throws rather than falling back to a weaker match. Calling this for a non-anchor
 * attribute type is a contract error: it throws instead of silently handing back the raw value.
 */
fun AttributeType.normalizeAnchorValue(value: String): String = when (this) {
    AttributeType.PERSON_ID -> value.trim().toLong().toString()
    AttributeType.EMAIL -> Email.of(value).value
    AttributeType.KVNR -> {
        // Format-validate first (Kvnr.of throws IllegalArgumentException for a malformed value,
        // same contract as any other bad input) - only THEN refuse the local-anchor question
        // itself: KVNR is resolved live through PersonDirectory (docs/ideen/account-attribute-
        // und-trust-vereinheitlichen.md), never stored/looked up as a local account.anchor row.
        Kvnr.of(value)
        error("$this is not a local account anchor - authority is $authority, resolved live via PersonDirectory")
    }
    AttributeType.NAME,
    AttributeType.VORNAME,
    AttributeType.GEBURTSDATUM,
    AttributeType.PHONE_NUMBER ->
        error("$this is not an anchor attribute (authority: $authority), has no normalized anchor value")
}

/**
 * Whether a newly established anchor value for this attribute type may replace a previous one
 * on the SAME account. `PERSON_ID` is `false` - immutable after first binding
 * (docs/ideen/account-attribute-und-trust-vereinheitlichen.md, "PersonId bleibt nach
 * Erstbindung unveraenderlich"); `EMAIL` is `true` - re-provable and therefore changeable.
 * `KVNR` is owned and changed by ext_stammdaten, not by the account's anchor projection.
 * Asking for a local replacement rule for it is a contract error.
 */
val AttributeType.allowsAnchorReplacement: Boolean
    get() = when (this) {
        AttributeType.PERSON_ID -> false
        AttributeType.EMAIL -> true
        AttributeType.KVNR,
        AttributeType.NAME,
        AttributeType.VORNAME,
        AttributeType.GEBURTSDATUM,
        AttributeType.PHONE_NUMBER ->
            error("$this has no anchor replacement rule defined - it is owned by $authority")
    }
