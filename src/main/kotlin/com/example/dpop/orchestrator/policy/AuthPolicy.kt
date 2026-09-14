package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.orchestrator.session.AcrLevel
import com.example.dpop.tool_spi.ToolId

/** The only place that knows what a *combination* of evidence means (docs/04-orchestrierung.md #2). */
interface AuthPolicy {
    /**
     * Does what THIS session has proven so far satisfy requiredAcr? [account] is needed to cap
     * any MFA combination bump by the enrolledUnderAcr of the methods involved - pass null only
     * when no account is resolvable yet (the bump is then conservatively withheld).
     */
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: AcrLevel, account: AccountProfile?): Boolean

    /**
     * Which of the account's AUTH tools could close the remaining gap right now? [bindingKeyRef]
     * (the calling channel's DPoP-proven device fingerprint) is needed to filter multi-instance
     * methods (device) down to the one instance that actually lives on THIS physical device -
     * offering one that doesn't would guarantee failure (docs/04-orchestrierung.md). Null on a
     * KEYCLOAK channel, which has no device - multi-instance methods never match there.
     *
     * [linkedAccountId] (the account `DeviceAccountLink` CURRENTLY names for [bindingKeyRef], if
     * any) additionally gates a multi-instance method: a device can only ever be actively bound to
     * one account, so a credential is only offered while the device is still linked to the SAME
     * account it belongs to - once rebound elsewhere, the old account's credential stops matching
     * here even though the raw key still equals [bindingKeyRef] (docs/09-dpop.md).
     *
     * [availableTools] narrows which AUTH tools this specific channel may even present (e.g. the
     * App frontend never declares `auth-qr`, docs/03-tool-architektur.md - there is no UI for it).
     * `null` means "don't filter" (every test/caller that genuinely doesn't care about this channel
     * restriction). This must be applied BEFORE deciding whether one active method alone already
     * suffices: an active-but-unofferable loa2-capable method must never silently suppress the
     * two-factor combination fallback for the methods that ARE actually offerable here - see
     * [DefaultAuthPolicy.candidateTools]'s own doc for the real bug this closes.
     */
    fun candidateTools(
        evidence: AuthEvidence,
        requiredAcr: AcrLevel,
        account: AccountProfile,
        bindingKeyRef: String?,
        linkedAccountId: Long?,
        availableTools: Set<ToolId>? = null
    ): List<ToolId>

    /**
     * Which IDENT tools (re-identification, e.g. ident-fsc) could ALSO close the remaining gap
     * right now? Deliberately separate from [candidateTools] rather than folded into it: an
     * identification's own maxAcr already prices in its full trust level (ident-fsc alone is
     * loa2) regardless of what the account has enrolled, so unlike AUTH candidates it isn't
     * gated by any per-account enrollment state - only callers that explicitly want to offer
     * re-identification as a step-up path (docs/04-orchestrierung.md, the MANAGE gate) opt in by
     * calling this at all; ordinary LOGIN/STEP_UP candidate resolution never does.
     */
    fun reIdentCandidates(evidence: AuthEvidence, requiredAcr: AcrLevel): List<ToolId>

    /** Could this account reach requiredAcr in a FUTURE login, given its current enrollments? */
    fun canAccountReach(account: AccountProfile, requiredAcr: AcrLevel): Boolean

    /**
     * Why [canAccountReach] is false for this account/requiredAcr pair - shown to the user
     * alongside "kein Verfahren fuehrt zum Ziel" so they know WHAT to change (usually: add a
     * method of a different factor type) instead of just that something is missing. Only ever
     * called once the caller already knows there's no way through.
     */
    fun unreachableReason(account: AccountProfile, requiredAcr: AcrLevel): String

    /** Which ENROLL tools would close the gap toward requiredAcr? */
    fun enrollmentCandidates(account: AccountProfile, requiredAcr: AcrLevel): List<ToolId>

    /**
     * Level implied by the given evidence. [account] is needed to cap any MFA combination bump
     * by the enrolledUnderAcr of the methods involved - pass null only when no account is
     * resolvable yet (the bump is then conservatively withheld).
     */
    fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): AcrLevel
}
