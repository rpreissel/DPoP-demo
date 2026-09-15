package com.example.dpop.tool_spi

/**
 * Nominal wrapper for a tool's public identifier ("auth-sms", "enroll-password", ...) - see
 * [ToolDescriptor.toolId]. Deliberately NOT the same type as a method name (e.g. "sms"): a toolId
 * names one concrete procedure ((method, role) pair, this interface's own doc), a method name
 * names the credential family several tools can share - a `value class` is what stops the two
 * from being silently interchangeable the way two `String` fields would be, at zero runtime cost.
 */
@JvmInline
value class ToolId(val value: String) {
    override fun toString(): String = value
}

/**
 * A tool's self-description. Implement this once per tool; the aggregation of every
 * implementation in the application context is the tool catalog - there is no separate, centrally
 * maintained list to keep in sync.
 */
interface ToolDescriptor {
    /** The tool's public, stable identifier, e.g. `"auth-sms"`. Never derived from other fields. */
    val toolId: ToolId

    /**
     * The credential family this tool belongs to, e.g. `"sms"`. Shared by several
     * [ToolDescriptor]s that play different [MethodRole]s for the same underlying credential - an
     * SMS enrollment tool, its device-auth tool, and its lookup-auth tool all use `method = "sms"`.
     */
    val method: String

    /** The role this tool plays for [method]. See [MethodRole]. Its [MethodRole.category] is the tool's coarse grouping. */
    val role: MethodRole

    /**
     * The step name a freshly activated tool session starts on. Must match the `nextStep` this
     * tool's own first `ToolOutcome.InProgress` actually returns. Defaults to [role]'s own
     * [MethodRole.defaultStartStep]; override only if this tool's first step is genuinely named
     * differently.
     */
    val startStep: String get() = role.defaultStartStep

    /** The factor kinds this tool can provide at most. */
    val factorTypes: Set<FactorType>

    /** The highest level this tool can achieve, e.g. [AcrLevel.LOA2]. */
    val maxAcr: AcrLevel

    /**
     * The identifying attributes a successful run of this tool asserts about its subject, each
     * with the [TrustAnchor] it is asserted with - carried as [Claim]s on
     * [ToolOutcome.Completed.Identified]/[ToolOutcome.Completed.Enrolled]. The declared
     * direction of the claims vocabulary, mirroring [factorTypes]: this set names what the
     * tool MAY assert on whose authority - the offer, answerable without any run having
     * happened (requires gates, catalog/matching planning); one concrete run's claims name
     * what it actually asserted, checked against this declaration by [assertClaimsCovered].
     */
    val claims: Set<ClaimDeclaration>
        get() = emptySet()

    /**
     * What the account must already have for this tool to be offered at all: each
     * [ClaimRequirement] is an [AttributeType] established at no less than its
     * [AnchorClass], checked against the account's consolidated value including
     * retractions (ADR-12). The mirror direction of [claims] - colloquially, "confirmed
     * email" is `ClaimRequirement(EMAIL, PROVEN)`. OFFERING gate only - it does not say the
     * tool consumes the value at run time (that is an anchor read), nor that a channel/journey
     * policy wants it (that is REGISTER's `emailObligation`, which keeps its own declarant).
     */
    val requires: Set<ClaimRequirement>
        get() = emptySet()

    /**
     * True if several active instances of this tool's method can coexist on one account at once
     * (e.g. one device credential per physical device), instead of the default rule where a new
     * enrollment replaces the previous active one.
     */
    val allowsMultipleInstances: Boolean
        get() = false

    /**
     * For an [allowsMultipleInstances] method: does the given active instance's `details` blob
     * belong to the caller identified by [callerBindingKeyRef]? Generic infrastructure that
     * iterates every multi-instance credential uniformly (`orchestrator.journey.CandidateTools`,
     * `orchestrator.policy.DefaultAuthPolicy`) calls this to pick "the" matching instance without
     * knowing HOW a concrete tool tells its instances apart - only the tool itself knows that
     * (`auth_device`'s own `"deviceBindingKeyRef"` detail key is private to that module, never
     * referenced outside it). tool_spi stays generic over every method exactly as
     * docs/03-tool-architektur.md #1 already requires for the rest of [ToolDescriptor]: "kein
     * toolId ist hier je ausgeschrieben" applies just as much to a concrete detail-map key.
     *
     * Default `true`: irrelevant for a tool that isn't actually multi-instance
     * ([allowsMultipleInstances] `false`), where callers never call this at all.
     */
    fun matchesCaller(details: Map<String, Any?>?, callerBindingKeyRef: String?): Boolean = true

    /**
     * The full "is this multi-instance credential still usable right now" check every caller
     * needs: [matchesCaller] (does the physical key match - descriptor-specific, e.g. `auth_device`'s
     * own detail key) AND the account-ownership invariant every multi-instance method shares alike -
     * a device can only ever be actively bound to ONE account at a time
     * ([com.example.dpop.orchestrator.session.DeviceAccountLink]), so a credential only counts as
     * usable while that link still names the SAME account it belongs to (docs/09-dpop.md). The
     * second half is never descriptor-specific, so it lives here once, not re-implemented by every
     * multi-instance [ToolDescriptor] - only [matchesCaller] is meant to be overridden.
     */
    fun matchesCurrentOwner(details: Map<String, Any?>?, callerBindingKeyRef: String?, linkedAccountId: Long?, accountId: Long): Boolean =
        matchesCaller(details, callerBindingKeyRef) && linkedAccountId == accountId
}

/** Coarse grouping of a tool. See [MethodRole.category]. */
enum class ToolCategory {
    IDENT,
    ENROLL,
    AUTH,

    /**
     * A tool that decides on another channel's pending request instead of proving or establishing
     * anything about its own channel/account - never contributes to the own channel's ACR/AMR
     * balance, never a candidate for closing a gap (docs/03-tool-architektur.md).
     */
    SIDE_ACTION
}

/**
 * The role a tool plays with respect to its [ToolDescriptor.method]. `(method, role)` together
 * uniquely identify a concrete procedure - `(method, category)` alone does not, since
 * [MethodRole.IDENTIFIED_AUTH] and [MethodRole.LOOKUP_AUTH] share `category=AUTH`.
 */
enum class MethodRole(val category: ToolCategory, val defaultStartStep: String) {
    /** Resolves identity (e.g. `ident-fsc`) - never establishes a durable credential. */
    IDENTIFICATION(ToolCategory.IDENT, "input"),

    /** Creates a new credential for the method (e.g. `enroll-sms`). */
    ENROLLMENT(ToolCategory.ENROLL, "enroll"),

    /**
     * Proves a credential for an account already known via the current channel/process (e.g.
     * `auth-sms`, `auth-device`).
     */
    IDENTIFIED_AUTH(ToolCategory.AUTH, "auth"),

    /**
     * Proves the same underlying credential as its [IDENTIFIED_AUTH] sibling, but resolves the
     * account itself from a submitted identifier (e.g. `auth-sms-lookup`) instead of relying on
     * the channel already knowing it.
     */
    LOOKUP_AUTH(ToolCategory.AUTH, "auth"),

    /**
     * Approves or declines a pending request that originated on a DIFFERENT channel (e.g.
     * `confirm-qr-login` deciding a `auth-qr`/`auth-qr-lookup` pairing,
     * docs/03-tool-architektur.md) - structurally unlike every other role, which answers
     * "who am I"/"what can I prove" for its OWN channel.
     */
    PEER_APPROVAL(ToolCategory.SIDE_ACTION, "input")
}

/** A kind of authentication factor a method can provide. */
enum class FactorType {
    /** Something the user knows, e.g. a password. */
    KNOWLEDGE,
    /** Something the user has, e.g. a phone or a device key. */
    POSSESSION,
    /** Something the user is, e.g. biometrics. */
    INHERENCE
}
