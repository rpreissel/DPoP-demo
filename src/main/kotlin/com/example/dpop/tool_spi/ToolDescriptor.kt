package com.example.dpop.tool_spi

/**
 * A tool's self-description. Implement this once per tool; the aggregation of every
 * implementation in the application context is the tool catalog - there is no separate, centrally
 * maintained list to keep in sync.
 */
interface ToolDescriptor {
    /** The tool's public, stable identifier, e.g. `"auth-sms"`. Never derived from other fields. */
    val toolId: String

    /** The credential family this tool belongs to. See [MethodFamily]. */
    val methodFamily: MethodFamily

    /** Convenience accessor for `methodFamily.method`, e.g. `"sms"`. */
    val method: String get() = methodFamily.method

    /** The role this tool plays for [method]. See [MethodRole]. */
    val role: MethodRole

    /** Coarse grouping; a passthrough of [role]'s own [MethodRole.category]. Do not override. */
    val category: ToolCategory get() = role.category

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
}

/**
 * Identifies a credential family shared by several [ToolDescriptor]s that play different
 * [MethodRole]s for the same underlying credential - e.g. an SMS enrollment tool, its device-auth
 * tool, and its lookup-auth tool all reference the same `MethodFamily("sms")` instance.
 *
 * Each module declares its own instance as a top-level `val` and references it from every
 * descriptor that shares the credential; `tool_spi` itself does not enumerate the possible
 * families.
 */
data class MethodFamily(val method: String)

/** Coarse grouping of a tool. See [MethodRole.category]/[ToolDescriptor.category]. */
enum class ToolCategory {
    IDENT,
    ENROLL,
    AUTH
}

/**
 * The role a tool plays with respect to its [MethodFamily]. `(methodFamily, role)` together
 * uniquely identify a concrete procedure - `(methodFamily, category)` alone does not, since
 * [MethodRole.DEVICE_AUTH] and [MethodRole.LOOKUP_AUTH] share `category=AUTH`.
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
    DEVICE_AUTH(ToolCategory.AUTH, "auth"),

    /**
     * Proves the same underlying credential as its [DEVICE_AUTH] sibling, but resolves the
     * account itself from a submitted identifier (e.g. `auth-sms-lookup`) instead of relying on
     * the channel already knowing it.
     */
    LOOKUP_AUTH(ToolCategory.AUTH, "auth")
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
