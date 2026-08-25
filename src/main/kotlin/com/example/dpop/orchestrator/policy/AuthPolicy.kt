package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile

/** The only place that knows what a *combination* of evidence means (docs/04-orchestrierung.md #2). */
interface AuthPolicy {
    /**
     * Does what THIS session has proven so far satisfy requiredAcr? [account] is needed to cap
     * any MFA combination bump by the enrolledUnderAcr of the methods involved - pass null only
     * when no account is resolvable yet (the bump is then conservatively withheld).
     */
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile?): Boolean

    /**
     * Which of the account's AUTH tools could close the remaining gap right now? [bindingKeyRef]
     * (the calling channel's DPoP-proven device fingerprint) is needed to filter multi-instance
     * methods (device) down to the one instance that actually lives on THIS physical device -
     * offering one that doesn't would guarantee failure (docs/04-orchestrierung.md).
     */
    fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile, bindingKeyRef: String): List<String>

    /**
     * Which IDENT tools (re-identification, e.g. ident-fsc) could ALSO close the remaining gap
     * right now? Deliberately separate from [candidateTools] rather than folded into it: an
     * identification's own maxAcr already prices in its full trust level (ident-fsc alone is
     * loa2) regardless of what the account has enrolled, so unlike AUTH candidates it isn't
     * gated by any per-account enrollment state - only callers that explicitly want to offer
     * re-identification as a step-up path (docs/04-orchestrierung.md, MANAGE_METHODS) opt in by
     * calling this at all; ordinary LOGIN/STEP_UP candidate resolution never does.
     */
    fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: String): List<String>

    /** Could this account reach requiredAcr in a FUTURE login, given its current enrollments? */
    fun canAccountReach(account: AccountProfile, requiredAcr: String): Boolean

    /** Which ENROLL tools would close the gap toward requiredAcr? */
    fun enrollmentCandidates(account: AccountProfile, requiredAcr: String): List<String>

    /**
     * Level implied by the given evidence. [account] is needed to cap any MFA combination bump
     * by the enrolledUnderAcr of the methods involved - pass null only when no account is
     * resolvable yet (the bump is then conservatively withheld).
     */
    fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): String
}
