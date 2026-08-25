package com.example.dpop.tool_spi

/**
 * Self-description a module brings for its tool; the aggregation of all descriptors IS
 * the tool catalog (docs/03-tool-architektur.md #1) - nothing is centrally maintained.
 */
interface ToolDescriptor {
    /** e.g. "auth-sms" */
    val toolId: String
    /** The shared credential family this tool belongs to (see [MethodFamily]) - object identity, not a re-typed string literal, is what connects enroll-sms/auth-sms/auth-sms-lookup. */
    val methodFamily: MethodFamily
    /** e.g. "sms" - never parsed out of toolId. */
    val method: String get() = methodFamily.method
    /** The role this tool plays for [method] - see [MethodRole] for why this replaced category+deviceBound. */
    val role: MethodRole

    /** Coarse routing/selection-context grouping, fully derived from [role] - never set independently, so it can't drift out of sync. */
    val category: ToolCategory
        get() = when (role) {
            MethodRole.IDENTIFICATION -> ToolCategory.IDENT
            MethodRole.ENROLLMENT -> ToolCategory.ENROLL
            MethodRole.DEVICE_AUTH, MethodRole.LOOKUP_AUTH -> ToolCategory.AUTH
        }

    /**
     * The step a freshly activated tool session starts on - the very value this tool's own first
     * `ToolOutcome.InProgress(nextStep = ...)` returns (docs/06-ablaeufe.md). Lives here, next to
     * that literal, rather than in a central toolId->step table in the orchestrator: such a table
     * has to be extended for every new tool and silently degrades to a wrong-but-plausible default
     * when someone forgets - which is exactly what had happened to `auth-email-lookup`.
     *
     * The [role]-derived default is correct for every tool today, so no module has to declare
     * anything; a tool whose first step is genuinely named differently overrides it, and that
     * override then sits in the same file as the `nextStep` literal it has to agree with.
     */
    val startStep: String
        get() = when (role) {
            MethodRole.IDENTIFICATION -> "input"
            MethodRole.ENROLLMENT -> "enroll"
            MethodRole.DEVICE_AUTH, MethodRole.LOOKUP_AUTH -> "auth"
        }

    val factorTypes: Set<FactorType>
    /** Highest level this procedure can carry, e.g. "loa2". */
    val maxAcr: String

    /**
     * Minimal, single-purpose precondition (docs/03-tool-architektur.md): true only for
     * enroll-password today, which needs a confirmed account email as its identifier before it
     * makes sense to offer. Deliberately a plain boolean, not a generic Set<Precondition> - that
     * generalization is premature for exactly one concrete case (docs/08-projektrahmen.md A11:
     * explicit over generic); revisit only once a second, differently-shaped precondition exists.
     */
    val requiresConfirmedEmail: Boolean
        get() = false

    /**
     * True only for `enroll-device`/`auth-device` today: several active instances of this method
     * can coexist on one account (one per physical device, each carrying its own credential and
     * user-chosen label) rather than the usual one-active-at-a-time rule
     * (docs/03-tool-architektur.md). `AccountService.addAuthenticationMethod` skips its normal
     * deactivate-the-old-one dedup when this is true; `AuthPolicy.enrollmentCandidates` keeps
     * offering the ENROLL tool even once one instance already exists. Declared independently per
     * tool variant, same as maxAcr/factorTypes - not forced to agree across every tool sharing a
     * method (docs/03-tool-architektur.md).
     */
    val allowsMultipleInstances: Boolean
        get() = false
}
