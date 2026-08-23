package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile

/** The only place that knows what a *combination* of evidence means (docs/04-orchestrierung.md #2). */
interface AuthPolicy {
    /** Does what THIS session has proven so far satisfy requiredAcr? */
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: String): Boolean

    /** Which of the account's AUTH tools could close the remaining gap right now? */
    fun candidateTools(evidence: AuthEvidence, requiredAcr: String, account: AccountProfile): List<String>

    /** Could this account reach requiredAcr in a FUTURE login, given its current enrollments? */
    fun canAccountReach(account: AccountProfile, requiredAcr: String): Boolean

    /** Which ENROLL tools would close the gap toward requiredAcr? */
    fun enrollmentCandidates(account: AccountProfile, requiredAcr: String): List<String>

    /** Level implied by the given evidence. */
    fun resolveAcr(evidence: AuthEvidence): String
}
