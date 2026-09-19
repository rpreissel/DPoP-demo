package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType

/**
 * The small, explicit rules that make some [AttributeType]s anchors - values accounts and
 * identities are looked up BY - and others mere projection attributes (docs/ideen/
 * claims-modell-und-vertrauensanker.md, "Zwei Sorten Auflösung"). `tool_spi` stays free of this
 * persistence-adjacent knowledge on purpose; it lives here in `tool_api` instead.
 */

/** Who owns the current value of an attribute. */
enum class AttributeAuthority {
    /** Stored locally in `account.anchor`, which is also the uniqueness authority for it. */
    LOCAL_ANCHOR,

    /**
     * Owned by the master-data backend and read live through [PersonDirectory] whenever it is
     * needed - never projected into a local column, so it cannot go stale. Local claim-log rows
     * for these types are history (what was asserted, by whom, when), never the current truth.
     */
    EXT_STAMMDATEN,

    /**
     * Owned by the method module that enrolled it, in its own `<module>_enrollment` row (e.g.
     * `auth_sms.enrollment.phone_number`). The `account` module never resolves it.
     */
    METHOD_MODULE
}

/**
 * The assurance an anchor write must be backed by. [establish] is the first binding of a value to
 * an account; [replace] re-points an account that already resolves by some other value - and that
 * is the attack, since the anchor is the lookup authority every lookup-login resolves through.
 */
data class AnchorAcrFloor(val establish: AcrLevel, val replace: AcrLevel)

/**
 * The anchor-specific rules for a [AttributeAuthority.LOCAL_ANCHOR] attribute. [bindingStrength]
 * is derived from [allowsReplacement] rather than a second, independently chosen rank: an anchor
 * nothing can ever replace is unforgeable evidence of identity, one that a later, equally strong
 * proof may re-point is not - so "immutable" and "binds more strongly" can never disagree.
 */
data class AnchorRule(val acrFloor: AnchorAcrFloor, val allowsReplacement: Boolean) {
    val bindingStrength: BindingStrength get() = BindingStrength.anchor(allowsReplacement)
}

/**
 * The complete rule set for one [AttributeType], as a single exhaustive `when` ([rule]) instead of
 * one `when` per property: a new [AttributeType] does not compile until this one place decides
 * everything about it. [anchor] is `null` exactly when [authority] is not
 * [AttributeAuthority.LOCAL_ANCHOR].
 */
data class AttributeRule(val authority: AttributeAuthority, val anchor: AnchorRule?)

/**
 * `PERSON_ID` establishes and replaces at `loa2` - it is the strongest anchor, so both writes cost
 * the most. `EMAIL` establishes at `loa1` - a registration has proven nothing yet, so demanding
 * `loa2` there would make the first account impossible - but replaces at `loa2`, since that write
 * is what would hand someone else's account to a new address.
 */
val AttributeType.rule: AttributeRule
    get() = when (this) {
        AttributeType.PERSON_ID -> AttributeRule(
            authority = AttributeAuthority.LOCAL_ANCHOR,
            anchor = AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false)
        )
        AttributeType.EMAIL -> AttributeRule(
            authority = AttributeAuthority.LOCAL_ANCHOR,
            anchor = AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true)
        )
        AttributeType.KVNR,
        AttributeType.NAME,
        AttributeType.VORNAME,
        AttributeType.GEBURTSDATUM -> AttributeRule(authority = AttributeAuthority.EXT_STAMMDATEN, anchor = null)
        AttributeType.PHONE_NUMBER -> AttributeRule(authority = AttributeAuthority.METHOD_MODULE, anchor = null)
    }

/**
 * Canonical form of [value] for storage and lookup, applied identically on write
 * (`AccountService` stores the normalized form) and on read (`AccountDirectory.resolveByAnchor`).
 * `PERSON_ID` normalizes to the canonical decimal representation of its `Long` value;
 * `EMAIL`/`KVNR` go through [Email]/[Kvnr] - an invalid value throws rather than falling back to a
 * weaker match. Calling this for a non-anchor attribute type is a contract error: it throws
 * instead of silently handing back the raw value.
 */
fun AttributeType.normalizeAnchorValue(value: String): String = when (this) {
    AttributeType.PERSON_ID -> value.trim().toLong().toString()
    AttributeType.EMAIL -> Email.of(value).value
    AttributeType.KVNR -> {
        // Format-validate first (Kvnr.of throws IllegalArgumentException for a malformed value,
        // same contract as any other bad input) - only THEN refuse the local-anchor question
        // itself: KVNR is resolved live through PersonDirectory, never stored/looked up as a
        // local account.anchor row.
        Kvnr.of(value)
        error("$this is not a local account anchor - authority is ${rule.authority}, resolved live via PersonDirectory")
    }
    AttributeType.NAME,
    AttributeType.VORNAME,
    AttributeType.GEBURTSDATUM,
    AttributeType.PHONE_NUMBER ->
        error("$this is not an anchor attribute (authority: ${rule.authority}), has no normalized anchor value")
}
