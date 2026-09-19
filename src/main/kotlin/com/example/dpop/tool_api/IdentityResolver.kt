package com.example.dpop.tool_api

import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim

/**
 * Central identity-resolution port: given the claims a tool just attested, does an existing
 * account already belong to them - and if so, which one?
 *
 * Layering (docs/ideen/claims-modell-und-vertrauensanker.md, "Identitaetsauflösung & Matching"):
 * tools attest, they never see accounts; the account module answers the account question,
 * because it owns the data the answer is computed from; the orchestrator only governs the
 * consequences of a [Resolution] (proceed, offer stronger procedures, or abort). One matching
 * policy - discriminants, normalization, thresholds - for every identification procedure,
 * instead of one per tool.
 *
 * Resolution precedence is fixed: anchor values first (unique, error-free - `person_id` ranks
 * highest among them, docs/ideen/account-attribute-und-trust-vereinheitlichen.md, "Gemeinsame
 * Aufloesung"), then attribute matching (ambiguous by nature - false merges and false splits
 * are its failure modes). A future EUDI-Wallet case slots in without a policy fork: an
 * issuer-scoped PID identifier arrives as its own anchor type, and selective disclosure simply
 * shrinks the claim set this method receives - a subset can only ever bind weakly.
 */
interface IdentityResolver {
    fun resolve(claims: Set<Claim>): Resolution
}

/** Result of resolving attested claims against the existing account stock. Never a boolean. */
sealed interface Resolution {
    /** An existing account owns (part of) these claims; [matchedVia] is mandatory provenance. */
    data class ExistingAccount(val accountId: Long, val matchedVia: MatchedVia) : Resolution

    /** Nothing in the stock matches - the identified subject has no account yet. */
    object NewInteressent : Resolution

    /**
     * Attribute matching produced more than one candidate - no automatic assignment.
     * [candidateCount] only, never the internal account ids themselves: unlike
     * `channelSessionId`, `accountId` is not meant to leave the account module, and nothing
     * downstream needs more than "how many" to abort the journey.
     */
    data class Ambiguous(val candidateCount: Int) : Resolution
}

/**
 * How strongly a resolution match binds an identity - the upgrade basis a later, stronger
 * identification may lift, never the other way. Each case names the property that sets it apart
 * from the next, not the concrete attribute types behind it: an anchor beats a mere attribute
 * combination (uniqueness), and among anchors, one nothing can ever replace beats one a later,
 * equally strong proof may re-point ([AnchorRule.bindingStrength] derives this directly from
 * [AnchorRule.allowsReplacement] - not a second, independently chosen rank).
 */
enum class BindingStrength {
    /** A normalized attribute combination (e.g. name+vorname+geburtsdatum) - ambiguous, no unique anchor at all. */
    ATTRIBUTE_COMBINATION,
    /** A unique anchor whose value a later, equally strong proof may replace (e.g. EMAIL). */
    REPLACEABLE_ANCHOR,
    /** A unique anchor that, once bound, is never replaced (e.g. PERSON_ID) - the strongest possible match. */
    IMMUTABLE_ANCHOR;

    companion object {
        fun anchor(allowsReplacement: Boolean) = if (allowsReplacement) REPLACEABLE_ANCHOR else IMMUTABLE_ANCHOR
    }
}

/** How a resolution matched, carrying its [BindingStrength] for [Resolution.ExistingAccount]. */
sealed interface MatchedVia {
    val bindingStrength: BindingStrength

    /** A unique anchor value (`person_id`, `email`) matched via `account.anchor`. */
    data class Anchor(val attributeType: AttributeType) : MatchedVia {
        override val bindingStrength = checkNotNull(attributeType.rule.anchor) {
            "$attributeType is not an anchor attribute, has no binding strength"
        }.bindingStrength
    }

    /** Normalized attribute combination (e.g. name + vorname + geburtsdatum) matched. */
    data class Attributes(val combination: Set<AttributeType>) : MatchedVia {
        override val bindingStrength = BindingStrength.ATTRIBUTE_COMBINATION
    }
}

/**
 * A tool-attested claim contradicts the person record behind its own anchor - e.g. eID
 * claims that don't line up with the ext_stammdaten person the kvnr resolves to. Thrown by
 * the central consistency check (tool-attested claims only; stammdaten-attested ones, like
 * ident-fsc's, were checked at the source). Surfaced by the orchestrator as an invalid
 * journey state - later in the chain than today's tool-PATCH rejection, so the journey
 * log keeps the decision point.
 */
class IdentityConflictException(message: String) : RuntimeException(message)
