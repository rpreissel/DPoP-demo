package com.example.dpop.tool_api

import com.example.dpop.texts.Text
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim

/**
 * Central identity-resolution port: given the claims a tool just attested, does an existing
 * account already belong to them - and if so, which one?
 *
 * Layering (docs/archiv/claims-modell-und-vertrauensanker.md, "Identitaetsauflösung & Matching"):
 * tools attest, they never see accounts; the account module answers the account question,
 * because it owns the data the answer is computed from; the orchestrator only governs the
 * consequences of a [Resolution] (proceed, offer stronger procedures, or abort). One matching
 * policy - discriminants, normalization, thresholds - for every identification procedure,
 * instead of one per tool.
 *
 * Resolution runs on anchor values only - unique, error-free lookups, `person_id` ranking
 * highest among them (docs/ideen/account-attribute-und-trust-vereinheitlichen.md, "Gemeinsame
 * Aufloesung"). There is deliberately no attribute matching against the account stock (ADR-19):
 * a combination like name+vorname+geburtsdatum is ambiguous by nature, and its values are only
 * ever checked against the master data behind a KVNR, never matched on their own. A future
 * EUDI-Wallet case slots in without a policy fork: an issuer-scoped PID identifier arrives as
 * its own anchor type, and selective disclosure simply shrinks the claim set this method
 * receives - a subset can only ever bind weakly.
 */
interface IdentityResolver {
    /**
     * Which account, if any, the just-attested [claims] already belong to.
     *
     * Only anchor-bearing claims take part (`PERSON_ID`, `VERSNR`, `EID_RESTRICTED_ID`,
     * `NECT_RESTRICTED_ID`, `EMAIL` - every `AttributeAuthority.Local` type); the rest
     * are carried along as attributes and never matched on. The verdict says how strongly the set
     * binds, so the caller can tell "this IS that account" from "this looks like it" without
     * re-deriving the rule - see [Resolution].
     *
     * @param claims everything one completed run asserted, anchors and attributes alike.
     * @return [Resolution.ExistingAccount] when an anchor resolved, [Resolution.Unresolved] when
     * none did - the caller then creates an account. Claims that contradict the person record
     * behind their own anchor do not come back as a verdict at all: they throw
     * [IdentityConflictException], so a contradiction can never be mistaken for a match.
     */
    fun resolve(claims: Set<Claim>): Resolution

    /**
     * Does the register's person [personId] match what account [accountId] has already had
     * ATTESTED about itself (name/vorname/geburtsdatum)?
     *
     * The guard for a correlation step like `ident-kvnr` (docs/12-entscheidungen.md ADR-18),
     * which turns a typed number into a register-vouched `PERSON_ID`: without it, attesting your
     * own identity and then typing a stranger's number would bind that stranger's anchor to your
     * account, whenever that stranger has no account yet.
     *
     * `false` when the account attested nothing to compare - a comparison against no attributes
     * would succeed vacuously ([ClaimedIdentity] skips `null` fields by design), which is exactly
     * the case this must refuse.
     */
    fun attestedIdentityMatches(accountId: Long, personId: String): Boolean
}

/** Result of resolving attested claims against the existing account stock. Never a boolean. */
sealed interface Resolution {
    /** An existing account owns (part of) these claims; [matchedVia] is mandatory provenance. */
    data class ExistingAccount(val accountId: Long, val matchedVia: MatchedVia) : Resolution

    /** Nothing in the stock matches - the identified subject has no account yet. */
    object Unresolved : Resolution
}

/**
 * How strongly a resolution match binds an identity - the upgrade basis a later, stronger
 * identification may lift, never the other way. Each case names the property that sets it apart
 * from the next, not the concrete attribute types behind it: among anchors, one nothing can ever
 * replace (PERSON_ID) beats one a later, equally strong proof may re-point (e.g. EMAIL, or the
 * eID card pseudonym - a new card re-points it to a new value, never to a new account)
 * ([AnchorRule.bindingStrength] derives this directly from [AnchorRule.allowsReplacement] - not a
 * second, independently chosen rank).
 */
enum class BindingStrength {
    /** A unique anchor whose value a later, equally strong proof may replace (e.g. EMAIL). */
    REPLACEABLE_ANCHOR,
    /** A unique anchor that, once bound, is never replaced (e.g. PERSON_ID) - the strongest possible match. */
    IMMUTABLE_ANCHOR;

    companion object {
        /**
         * Derives the strength from the anchor rule's own [AnchorRule.allowsReplacement], so the
         * two can never disagree - the rank is read off the existing rule, never chosen a second
         * time alongside it.
         */
        fun anchor(allowsReplacement: Boolean) = if (allowsReplacement) REPLACEABLE_ANCHOR else IMMUTABLE_ANCHOR
    }
}

/** How a resolution matched, carrying its [BindingStrength] for [Resolution.ExistingAccount]. */
sealed interface MatchedVia {
    /** How strongly this particular match binds - what an upgrade decision is made on. */
    val bindingStrength: BindingStrength

    /** A unique anchor value (`person_id`, `versnr`, `restricted_id`, `nect_restricted_id`, `email`) matched via `account.anchor`. */
    data class Anchor(val attributeType: AttributeType) : MatchedVia {
        override val bindingStrength = checkNotNull(attributeType.anchorRule) {
            "$attributeType is not an anchor attribute, has no binding strength"
        }.bindingStrength
    }
}

/**
 * A tool-attested claim contradicts the person record behind its own anchor - e.g. eID
 * claims that don't line up with the ext_personenverzeichnis person the kvnr resolves to. Thrown by
 * the central consistency check (tool-attested claims only; stammdaten-attested ones, like
 * ident-fsc's, were checked at the source). Surfaced by the orchestrator as an invalid
 * journey state - later in the chain than today's tool-PATCH rejection, so the journey
 * log keeps the decision point.
 */
class IdentityConflictException(val text: Text, detail: String? = null) :
    RuntimeException(detail?.let { "${text.template} ($it)" } ?: text.template)
