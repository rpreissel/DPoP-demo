package com.example.dpop.orchestrator.policy

import com.example.dpop.account.AccountProfile
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.FactorType
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
     * Which of the account's AUTH tools could close the remaining gap right now?
     * [CandidateContext.bindingKeyRef]
     * (the calling channel's DPoP-proven device fingerprint) is needed to filter multi-instance
     * methods (device) down to the one instance that actually lives on THIS physical device -
     * offering one that doesn't would guarantee failure (docs/04-orchestrierung.md). Null on a
     * KEYCLOAK channel, which has no device - multi-instance methods never match there.
     *
     * [CandidateContext.linkedAccountId] (the account `DeviceAccountLink` CURRENTLY names for
     * [CandidateContext.bindingKeyRef], if any) additionally gates a multi-instance method:
     * a device can only ever be actively bound to
     * one account, so a credential is only offered while the device is still linked to the SAME
     * account it belongs to - once rebound elsewhere, the old account's credential stops
     * matching here even though the raw key still equals [CandidateContext.bindingKeyRef]
     * (docs/09-dpop.md).
     *
     * [CandidateContext.availableTools] narrows which AUTH tools this specific channel may even
     * present (e.g. the App frontend never declares `auth-qr`,
     * docs/03-tool-architektur.md - there is no UI for it).
     * `null` means "don't filter" (every test/caller that genuinely doesn't care about this channel
     * restriction). This must be applied BEFORE deciding whether one active method alone already
     * suffices: an active-but-unofferable loa2-capable method must never silently suppress the
     * two-factor combination fallback for the methods that ARE actually offerable here - see
     * [DefaultAuthPolicy.authCandidates]'s own doc for the real bug this closes.
     */
    fun authCandidates(ctx: CandidateContext): List<ToolId>

    /**
     * Which IDENT tools (re-identification, e.g. ident-fsc) could ALSO close the remaining gap
     * right now? Deliberately separate from [authCandidates] rather than folded into it: an
     * identification's own maxAcr already prices in its full trust level (ident-fsc alone is
     * loa2) regardless of what the account has enrolled, so unlike AUTH candidates it isn't
     * gated by any per-account enrollment state - only callers that explicitly want to offer
     * re-identification as a step-up path (docs/04-orchestrierung.md, the MANAGE gate) opt in by
     * calling this at all; ordinary LOGIN/STEP_UP candidate resolution never does.
     */
    fun reIdentCandidates(ctx: CandidateContext): List<ToolId>

    /**
     * Could this account reach requiredAcr in a FUTURE login, given its current enrollments - and
     * if not, why (so the caller can tell the user what's missing, usually: add a method of a
     * different factor type)? One method, not two separate `canAccountReach`/`unreachableReason`
     * calls: every caller that needs the reason only ever needs it once the boolean is already
     * false, and a second call would just recompute the exact same account-standing calculation -
     * see the `*Strategy` abort branches (`AuthEnrollCore`, `RegisterEnrollFirstStrategy`,
     * `StepUpStrategy`, `LookupLoginStrategy`), which used to call both against the same
     * (account, requiredAcr) pair. A caller that only wants the boolean checks
     * `is Reachability.Reachable`; [Reachability.NotReachable.reason] is a structured
     * [UnreachableReason], never pre-rendered text - turning it into a (German) user-facing
     * message is the CALLER's job (`orchestrator.journey.AbortMessages`), not the policy's,
     * exactly like [AcrLevel]/[com.example.dpop.tool_spi.ToolId] keep their own String value from
     * leaking meaning the type itself should carry.
     */
    fun reachability(account: AccountProfile, requiredAcr: AcrLevel): Reachability

    /** Which ENROLL tools would close the gap toward requiredAcr? */
    fun enrollmentCandidates(ctx: CandidateContext): List<ToolId>

    /**
     * Level implied by the given evidence. [account] is needed to cap any MFA combination bump
     * by the enrolledUnderAcr of the methods involved - pass null only when no account is
     * resolvable yet (the bump is then conservatively withheld).
     */
    fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): AcrLevel
}

/** Shared context for candidate resolution methods. */
data class CandidateContext(
    val evidence: AuthEvidence,
    val requiredAcr: AcrLevel,
    val account: AccountProfile? = null,
    val bindingKeyRef: String? = null,
    val linkedAccountId: Long? = null,
    val availableTools: Set<ToolId>? = null
)

/** Result of [AuthPolicy.reachability] - never a bare `Boolean`, so a caller can't check reachability without the compiler forcing it to also handle the (structured) reason once it's false. */
sealed interface Reachability {
    /** requiredAcr is reachable with the account's current standing methods. */
    data object Reachable : Reachability

    /** requiredAcr is NOT reachable - [reason] is WHY, for a caller (e.g. `orchestrator.journey.AbortMessages`) to render. */
    data class NotReachable(val reason: UnreachableReason) : Reachability
}

/**
 * WHY [Reachability.NotReachable] - a plain domain fact, deliberately not a pre-formatted
 * (German) message: exactly the same reasoning that keeps [AcrLevel]/[com.example.dpop.tool_spi.ToolId]
 * from being bare `String`s applies here to a whole explanation, not just an identifier - the
 * policy layer names WHAT is missing, the caller decides HOW to say it (and in which language).
 */
sealed interface UnreachableReason {
    /** No active authentication method at all. */
    data object NoActiveMethod : UnreachableReason

    /**
     * The active methods together cover fewer than 2 distinct factor types. [methods]/[factorTypes]
     * are what IS active, so a caller can name what's missing without recomputing anything.
     */
    data class SingleFactorType(val methods: List<String>, val factorTypes: Set<FactorType>) : UnreachableReason

    /**
     * Coverage (level + factor types) would suffice, but every combining method was enrolled under
     * a lower ACR than required - [maxEnrolledUnderAcr] is the highest any of them actually reached.
     */
    data class CombinationCapped(val maxEnrolledUnderAcr: AcrLevel) : UnreachableReason

    /** Same as [CombinationCapped], but for the single-method case (no combination involved at all). */
    data class SingleMethodCapped(val method: String, val maxEnrolledUnderAcr: AcrLevel) : UnreachableReason
}
