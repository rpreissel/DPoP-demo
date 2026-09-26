package com.example.dpop.orchestrator.domain.journey

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.domain.policy.AuthEvidence
import com.example.dpop.orchestrator.domain.policy.EvidenceAxis
import com.example.dpop.texts.Text
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId

/*
 * Which account an executed action writes to - the rules that keep a session from ending up bound to
 * an account it never proved it owns. Pure: every fact comes in as a value (or, where it costs a
 * lookup and is only needed in one branch, as a function). `JourneyActionExecutor` reads, asks here,
 * and writes (docs/ideen/fachkern-und-technik-trennen.md).
 *
 * A refusal is an [IdentityConflictException] (409): each of them is a request that would merge two
 * people or two accounts, never a broken assumption.
 */

/** Where an identification's attested claims land when they resolved to no existing account. */
sealed interface IdentificationTarget {
    /** Nothing in hand: a fresh account, created in the same transaction as its claims. */
    data object NewAccount : IdentificationTarget

    /** The account in hand takes the attestation. */
    data class AccountInHand(val accountId: Long) : IdentificationTarget

    companion object {
        /**
         * - **Nothing in hand:** a new account.
         * - **An account without a person binding** takes the attestation (ADR-10): the REGISTER
         *   "Enrollment zuerst" account finally getting its identity, a provisional one attested a
         *   second time, or the account a correlation step is extending. Opening a SECOND account
         *   beside it would silently split one run across two. But only as the SAME person: an
         *   Interessent that already attested an identity must not take a second one
         *   ([attestationFits], review 2026-09, Phase F).
         * - **An identified account** plus an attestation that resolves to nobody means a DIFFERENT
         *   person - mixing a stranger's attested identity into it is the one thing that must never
         *   happen quietly. Somebody else registering on a linked phone does not get here:
         *   `intent=register` never takes the device's account (`AuthIntent.startsFromDeviceLink`).
         */
        fun forUnresolved(inHand: AccountProfile?, attestationFits: () -> Boolean): IdentificationTarget = when {
            inHand == null -> NewAccount
            inHand.isUnidentified ->
                if (attestationFits()) AccountInHand(inHand.accountId)
                else throw IdentityConflictException(Text("Die bezeugte Identitaet gehoert nicht zu dem Konto dieser Sitzung"))
            else -> throw IdentityConflictException(Text("Die bezeugte Identitaet gehoert nicht zu dem Konto dieser Sitzung"))
        }
    }
}

/**
 * Two accounts meet in one run - the one in hand and the one an identification or attestation
 * resolved to (ADR-20). The rule is one sentence: **the provisional account is absorbed into the
 * other one**, whichever of the two it is; if neither is provisional, nothing moves.
 */
sealed interface AccountMerge {
    /**
     * The account IN HAND is provisional - the ident-first case: the journey had to open a
     * placeholder for an attestation that resolved nobody, and the correlation step then found the
     * real account. The session moves over and takes the attestation along. Also the answer when
     * both are provisional: moving to the resolved account keeps the anchor that did the resolving
     * where the rest of the stock expects it.
     */
    data class MoveInto(val from: Long, val into: Long) : AccountMerge

    /**
     * The RESOLVED account is provisional - the "Enrollment zuerst" mirror image: the account in
     * hand holds the credentials this run just created; the resolved one is a placeholder an earlier,
     * abandoned eID run left behind, found again through its `restricted_id` anchor (ADR-19). The
     * session stays and absorbs it - without this, that leftover would block its own card forever.
     */
    data class AbsorbResolved(val resolved: Long, val into: Long) : AccountMerge

    companion object {
        /**
         * Neither provisional is refused: a credential was enrolled or a person bound on both, so two
         * real accounts would be merging - a decision for an explicit account merge, never a side
         * effect of an identification step. [resolved] is only looked up when the account in hand
         * is not provisional itself.
         */
        fun decide(inHand: AccountProfile, resolved: () -> AccountProfile): AccountMerge {
            if (inHand.isProvisional) return MoveInto(from = inHand.accountId, into = resolved().accountId)
            val other = resolved()
            if (other.isProvisional) return AbsorbResolved(resolved = other.accountId, into = inHand.accountId)
            throw IdentityConflictException(Text("Identification claims resolve to a different account"))
        }
    }
}

/**
 * A correlation step (`MethodRole.CORRELATION`, ident-kvnr, ADR-18) proves nothing about the subject
 * itself - it only turns a typed number into a register-vouched person. Its whole security argument
 * is that this person matches what the account in hand already had attested; without the check,
 * attesting yourself and then typing a stranger's number would bind their anchor here whenever that
 * stranger has no account of their own yet.
 *
 * - An account that already HAS a person is refused outright: attaching another one would be a
 *   change of identity bought with no proof at all.
 * - A run that resolved nobody has nothing to correlate and must not pass silently.
 * - The resolved person must match the attested identity ([matches]). The tool asked the same
 *   question before reporting; failing here means a tool skipped that.
 */
fun checkCorrelation(account: AccountProfile, toolId: ToolId, claimedPersonId: String?, matches: (String) -> Boolean) {
    if (account.personId != null) {
        throw IdentityConflictException(Text("Dieses Konto ist bereits einer Person zugeordnet"))
    }
    val personId = checkNotNull(claimedPersonId) { "$toolId completed as a correlation without resolving a person" }
    if (!matches(personId)) {
        throw IdentityConflictException(Text("Die Versichertennummer gehoert nicht zu der nachgewiesenen Identitaet"))
    }
}

/**
 * An attested anchor value (e.g. a confirmed address) that resolves to ANOTHER account may move this
 * session there ONLY if the session has already proven a real identification ([EvidenceAxis.IDENTITY]
 * evidence, accumulated across the channel's journeys, review 2026-09, M-7) - never on the strength
 * of the attestation alone. This is what makes REGISTER "Enrollment zuerst" safe: its first step is
 * `confirm-email`, before any identification, so a new session cannot re-confirm somebody else's
 * address and be bound to their account.
 *
 * Once an identification did run, the attested identity must also FIT a target with a register
 * person ([matches], the same guard ADR-18 puts in front of the correlation step): possession of an
 * address says "this mailbox is mine", never "I am that person".
 */
fun checkAttestationMove(evidence: AuthEvidence, targetPersonId: String?, matches: (String) -> Boolean) {
    if (evidence.factors.none { it.axis == EvidenceAxis.IDENTITY }) {
        throw IdentityConflictException(Text("Diese Adresse gehoert bereits zu einem anderen Konto"))
    }
    if (targetPersonId != null && !matches(targetPersonId)) {
        throw IdentityConflictException(Text("Diese Adresse gehoert zu einer anderen Person"))
    }
}

/**
 * Which account a proven credential belongs to. Only [MethodRole.LOOKUP_AUTH] may NAME one - that
 * role's contract is "resolves the account itself from a submitted identifier"; every other role
 * proves a credential OF the account the channel already knows. Even a lookup tool's account must
 * agree with one already in hand: nothing legitimately re-resolves a different account mid-journey,
 * that is the silent switch this whole class of bug is made of.
 */
fun accountOfProof(role: MethodRole, namedByTool: Long?, inHand: Long?): Long {
    val named = namedByTool?.takeIf { role == MethodRole.LOOKUP_AUTH }
    if (named != null && inHand != null && named != inHand) {
        throw IdentityConflictException(Text("Der Nachweis gehoert zu einem anderen Konto als dieser Sitzung"))
    }
    return checkNotNull(named ?: inHand) { "Authenticated without a known account" }
}
