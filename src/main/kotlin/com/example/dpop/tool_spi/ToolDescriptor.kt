package com.example.dpop.tool_spi

/**
 * A tool's self-description. Implement this once per tool; the aggregation of every
 * implementation in the application context is the tool catalog - there is no separate, centrally
 * maintained list to keep in sync.
 */
interface ToolDescriptor {
    /** The tool's public, stable identifier, e.g. `"auth-sms"`. Never derived from other fields. */
    val toolId: String

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

    /** The highest level this tool can achieve, e.g. `"loa2"`. */
    val maxAcr: String

    /** True if this tool requires the account to already have a confirmed email to be offered. */
    val requiresConfirmedEmail: Boolean
        get() = false

    /** True if a successful run of this tool leaves the account with a confirmed email. */
    val confirmsAccountEmail: Boolean
        get() = false

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
