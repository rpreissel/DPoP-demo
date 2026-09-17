package com.example.dpop.tool_api

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
 * The binding strength an anchor match on this attribute type carries, or `null` if this
 * attribute is no anchor at all. `PERSON_ID` outranks the others (3) - it is the strongest
 * possible identity match; `EMAIL` has rank 2. KVNR is resolved live through PersonDirectory,
 * not stored as a local account anchor. A new anchor kind is a deliberate new
 * case here, not a silently-added `if`.
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
        // und-trust-vereinheitlichen.md), never stored/looked up as a local account_anchor row.
        Kvnr.of(value)
        error("$this is not a local account anchor - resolved live via PersonDirectory")
    }
    AttributeType.NAME,
    AttributeType.VORNAME,
    AttributeType.GEBURTSDATUM,
    AttributeType.PHONE_NUMBER -> error("$this is not an anchor attribute, has no normalized anchor value")
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
        AttributeType.PHONE_NUMBER -> error("$this has no anchor replacement rule defined")
    }
