package com.example.dpop.tool_api

import com.example.dpop.tool_spi.Partnernr
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType

/**
 * The small, explicit rules that make some [AttributeType]s anchors - values accounts and
 * identities are looked up BY - and others mere projection attributes (docs/02-domaenenmodell.md,
 * Abschnitt 6; ADR-19). `tool_spi` stays free of this
 * persistence-adjacent knowledge on purpose; it lives here in `tool_api` instead.
 */

/**
 * Who owns the current value of an attribute - and, for the one owner that also resolves accounts
 * by it, the rules that come with that. Sealed rather than an enum beside a nullable [AnchorRule]:
 * "the account owns this value" and "this value is an anchor" are one fact here, not two that a
 * test has to keep in step. There is no locally owned attribute that is not an anchor, and no
 * anchor that is not locally owned, so [Local] carries its [Local.anchor] and the unpaired state
 * cannot be written down.
 */
sealed interface AttributeAuthority {
    /**
     * The account itself owns the value, stored in `account.anchor` - which is also the uniqueness
     * authority for it and the row every lookup resolves through, hence [anchor].
     */
    data class Local(val anchor: AnchorRule) : AttributeAuthority

    /**
     * Owned by the master-data backend and read live through the [PersonDirectory][com.example.dpop.tool_api.PersonDirectory] port whenever it is
     * needed - never projected into a local column, so it cannot go stale. Local claim-log rows
     * for these types are history (what was asserted, by whom, when), never the current truth.
     */
    data object PersonDirectory : AttributeAuthority

    /**
     * Owned by the method module that enrolled it, in its own `<module>.enrollment` row (e.g.
     * `auth_sms.enrollment.phone_number`). The `account` module never resolves it.
     */
    data object MethodModule : AttributeAuthority
}

/**
 * The assurance an anchor write must be backed by. [establish] is the first binding of a value to
 * an account; [replace] re-points an account that already resolves by some other value - and that
 * is the attack, since the anchor is the lookup authority every lookup-login resolves through.
 */
data class AnchorAcrFloor(val establish: AcrLevel, val replace: AcrLevel)

/**
 * The anchor-specific rules an [AttributeAuthority.Local] attribute carries. [bindingStrength]
 * is derived from [allowsReplacement] rather than a second, independently chosen rank: an anchor
 * nothing can ever replace is unforgeable evidence of identity, one that a later, equally strong
 * proof may re-point is not - so "immutable" and "binds more strongly" can never disagree.
 *
 * [retractableByHolder]: whether the account holder may withdraw the value in self-service. Only
 * a contact channel is theirs to give up; an identity anchor is not - withdrawing PERSON_ID would
 * turn an identified account back into a never-identified one and open it to a correlation with
 * somebody else, undoing exactly what [allowsReplacement] = false forbids (review 2026-09, S-7).
 * Stated per type, never defaulted, so a new anchor has to decide.
 */
data class AnchorRule(val acrFloor: AnchorAcrFloor, val allowsReplacement: Boolean, val retractableByHolder: Boolean) {
    val bindingStrength: BindingStrength get() = BindingStrength.anchor(allowsReplacement)
}

/**
 * Who owns this attribute's current value, as a single exhaustive `when` instead of one `when` per
 * property: a new [AttributeType] does not compile until this one place decides everything about
 * it.
 *
 * `PERSON_ID` establishes and replaces at `loa2` - it is the strongest anchor, so both writes cost
 * the most. `EMAIL` establishes at `loa1` - a registration has proven nothing yet, so demanding
 * `loa2` there would make the first account impossible - but replaces at `loa2`, since that write
 * is what would hand someone else's account to a new address. `EID_RESTRICTED_ID` establishes and
 * replaces at `loa2` (ADR-19): the card pseudonym is only ever proven by a full eID read, and
 * replacing it (a new card, same person) must cost exactly what establishing it did - it can
 * change value, but never account. `NECT_RESTRICTED_ID` follows the same rule: the same card, read
 * by Nect, carries Nect's own pseudonym (§18 PAuswG).
 */
val AttributeType.authority: AttributeAuthority
    get() = when (this) {
        AttributeType.PERSON_ID -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false, retractableByHolder = false)
        )
        // Set when a person with a Versicherungsnummer is bound, replaced/released when the
        // Personenverzeichnis reports a change (ADR-34) - can change value, never account.
        AttributeType.INSURANCE_NUMBER -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false)
        )
        AttributeType.EID_RESTRICTED_ID,
        AttributeType.NECT_RESTRICTED_ID -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false)
        )
        AttributeType.EMAIL -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = true)
        )
        AttributeType.KVNR,
        AttributeType.FAMILY_NAME,
        AttributeType.GIVEN_NAMES,
        AttributeType.BIRTH_DATE,
        AttributeType.STREET_ADDRESS,
        AttributeType.POSTAL_CODE,
        AttributeType.LOCALITY -> AttributeAuthority.PersonDirectory
        AttributeType.PHONE_NUMBER,
        // MethodModule is what makes the dependency work: retractClaimsOf retracts exactly these
        // when the owning method instance is revoked, so "the account has a password" stops being
        // established the moment the password does.
        AttributeType.PASSWORD_EXISTS -> AttributeAuthority.MethodModule
    }

/**
 * The anchor rules of this attribute type, or `null` for a type the account does not own itself.
 * Shorthand for the `is`-check plus the property, for the callers that only want the rules; the
 * ones that branch on ownership match on [authority] directly.
 */
val AttributeType.anchorRule: AnchorRule?
    get() = (authority as? AttributeAuthority.Local)?.anchor

/** Whether the account itself owns this attribute's value - i.e. whether it is a local anchor. */
val AttributeType.isLocalAnchor: Boolean
    get() = authority is AttributeAuthority.Local

/**
 * Canonical form of [value] for storage and lookup, applied identically on write
 * (`AccountService` stores the normalized form) and on read (`AccountDirectory.resolveByAnchor`).
 * `PERSON_ID`/`VERSNR`/`EMAIL`/`KVNR` go through [Partnernr]/[Versnr]/[Email]/[Kvnr] - an invalid value throws rather than falling back to a
 * weaker match. Calling this for a non-anchor attribute type is a contract error: it throws
 * instead of silently handing back the raw value.
 */
fun AttributeType.normalizeAnchorValue(value: String): String = when (this) {
    AttributeType.PERSON_ID -> Partnernr.of(value).value
    AttributeType.EID_RESTRICTED_ID,
    AttributeType.NECT_RESTRICTED_ID -> value.trim()
    AttributeType.INSURANCE_NUMBER -> Versnr.of(value).value
    AttributeType.EMAIL -> Email.of(value).value
    AttributeType.KVNR -> {
        // Format-validate first (Kvnr.of throws IllegalArgumentException for a malformed value,
        // same contract as any other bad input) - only THEN refuse the local-anchor question
        // itself: KVNR is resolved live through PersonDirectory, never stored/looked up as a
        // local account.anchor row.
        Kvnr.of(value)
        error("$this is not a local account anchor - authority is $authority, resolved live via PersonDirectory")
    }
    AttributeType.FAMILY_NAME,
    AttributeType.GIVEN_NAMES,
    AttributeType.BIRTH_DATE,
    AttributeType.STREET_ADDRESS,
    AttributeType.POSTAL_CODE,
    AttributeType.LOCALITY,
    AttributeType.PHONE_NUMBER,
    AttributeType.PASSWORD_EXISTS ->
        error("$this is not an anchor attribute (authority: $authority), has no normalized anchor value")
}
