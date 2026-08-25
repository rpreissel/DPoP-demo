package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile

/**
 * A pending step that must be resolved before a process can finish - Keycloak calls this a
 * "Required Action" (VERIFY_EMAIL, UPDATE_PASSWORD, ...). Deliberately derived from EXISTING
 * account/evidence state here, not stored as its own account field: every Required Action defined
 * today (see [ToolOutcomeProcessor][com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor])
 * is a pure function of data that already exists (`authenticationMethods`, `emailConfirmedAt`), so
 * a separate `pendingRequiredActions` column would only risk drifting out of sync with reality for
 * no benefit. Add a stored, per-account-assignable variant only once a concrete action shows up
 * that genuinely can't be derived this way (e.g. an admin-triggered one-off task) - this interface
 * doesn't need to change for that, only a new implementation would.
 */
interface RequiredAction {
    val name: String

    fun isSatisfied(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String): Boolean

    /**
     * Only ever called when [isSatisfied] is false. Second value is the offerCandidates context
     * ("auth"/"enrollment"). [bindingKeyRef] is the calling channel's DPoP-proven device
     * fingerprint, needed to filter multi-instance methods (device) down to the instance that
     * lives on THIS physical device - unused by actions that never offer such a method.
     */
    fun candidates(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String, bindingKeyRef: String): Pair<List<String>, String>
}

/**
 * The ACR-sufficiency check itself, reframed as the first (and previously only) Required Action:
 * "has this account set up enough to reach requiredAcr". Pure delegation to [AuthPolicy] - no new
 * decision logic, only a name and a place in the ordered list.
 */
class SufficientLoginMethodRequiredAction(private val authPolicy: AuthPolicy) : RequiredAction {
    override val name = "sufficient-login-method"

    override fun isSatisfied(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String): Boolean =
        authPolicy.canAccountReach(account, requiredAcr) && authPolicy.isSatisfied(evidence, requiredAcr, account)

    override fun candidates(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String, bindingKeyRef: String): Pair<List<String>, String> =
        if (authPolicy.canAccountReach(account, requiredAcr)) {
            authPolicy.candidateTools(evidence, requiredAcr, account, bindingKeyRef) to "auth"
        } else {
            authPolicy.enrollmentCandidates(account, requiredAcr) to "enrollment"
        }
}

/**
 * A confirmed email is required so a chosen password has an identifier to hang off of
 * (`ToolDescriptor.requiresConfirmedEmail`, docs/03-tool-architektur.md #1) - without this,
 * registration could finish (e.g. via sms alone) and leave enroll-password permanently
 * unreachable. Only part of REGISTRATION's Required Action list
 * ([ToolOutcomeProcessor][com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor]) -
 * existing accounts without a confirmed email are not retroactively blocked from LOGIN/STEP_UP.
 */
class ConfirmedEmailRequiredAction : RequiredAction {
    override val name = "confirmed-email"

    override fun isSatisfied(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String): Boolean =
        account.emailConfirmed

    override fun candidates(account: AccountProfile, evidence: AuthEvidence, requiredAcr: String, bindingKeyRef: String): Pair<List<String>, String> =
        listOf("enroll-email") to "enrollment"
}
