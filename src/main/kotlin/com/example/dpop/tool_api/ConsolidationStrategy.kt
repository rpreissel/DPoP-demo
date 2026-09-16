package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType

/**
 * How a claimed [AttributeType]'s current value is made available after
 * `AccountService.recordClaim` logs it - the one place this decision lives, mirroring
 * [AttributeRules]' own reasoning (docs/ideen/claims-modell-und-vertrauensanker.md, "Identitaets-
 * aufloesung & Matching"): a new attribute type is a new case with its own consolidation rule,
 * not a silently-added `if`.
 */
sealed interface ConsolidationStrategy {
    /**
     * The account module is itself authoritative once this is established: a dedicated column
     * on `Account`, consolidated synchronously (plus its anchor, if [anchorBindingStrength] is
     * non-null, and an `AccountChanged` event) - `EMAIL` and, since docs/ideen/account-attribute-
     * und-trust-vereinheitlichen.md (Paket 4), `PERSON_ID` too: same technical path, same
     * write-time consolidation, only the type-specific rules in `AttributeRules.kt`
     * ([AttributeType.allowsAnchorReplacement] is `false` for `PERSON_ID`) differ.
     */
    data object OwnedColumn : ConsolidationStrategy

    /**
     * Authority lives elsewhere (ext_stammdaten), reachable via the account's own `personId` -
     * nothing is stored beyond the provenance log entry `recordClaim` always writes. Every
     * reader (Keycloak sync, a future consolidated read) resolves the current value live via
     * `personId`, never from a cached copy: `KVNR`, `NAME`, `VORNAME` and `GEBURTSDATUM` are
     * asserted together with `PERSON_ID`, from the same trust anchor, in the same instant
     * (`IdentFscToolHandler`/`IdentEidToolHandler`) - there is only ever ONE event ("person X
     * identified"), never four independent ones, so there is nothing per-attribute to cache: the
     * `personId` claim alone already carries everything needed to re-derive the rest on demand.
     */
    data object ExternalLiveLookup : ConsolidationStrategy
}

/**
 * See [ConsolidationStrategy]. `PHONE_NUMBER` is provisionally [ConsolidationStrategy.ExternalLiveLookup]
 * pending [anchorBindingStrength]'s own "next candidate" note - revisit together once phone_number
 * actually becomes an anchor.
 */
fun AttributeType.consolidationStrategy(): ConsolidationStrategy = when (this) {
    AttributeType.EMAIL,
    AttributeType.PERSON_ID -> ConsolidationStrategy.OwnedColumn
    AttributeType.KVNR,
    AttributeType.NAME,
    AttributeType.VORNAME,
    AttributeType.GEBURTSDATUM,
    AttributeType.PHONE_NUMBER -> ConsolidationStrategy.ExternalLiveLookup
}
