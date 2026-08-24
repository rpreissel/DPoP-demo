package com.example.dpop.tool_spi

/**
 * Self-description a module brings for its tool; the aggregation of all descriptors IS
 * the tool catalog (docs/03-tool-architektur.md #1) - nothing is centrally maintained.
 */
interface ToolDescriptor {
    /** e.g. "auth-sms" */
    val toolId: String
    val category: ToolCategory
    /** e.g. "sms" - connects enroll-sms and auth-sms; never parsed out of toolId. */
    val method: String
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
     * True for every tool except the `-lookup` AUTH variants (docs/04-orchestrierung.md,
     * lookup-based login): a device-bound tool assumes the account is already resolved via the
     * channel/process before it activates. A `-lookup` tool resolves the account ITSELF from a
     * submitted email and is therefore only ever reachable through the dedicated lookup-login
     * entry point, never through AuthPolicy.candidateTools' generic per-method resolution -
     * without this flag, a lookup tool sharing the same `method` as its device-bound sibling
     * (both genuinely prove the same underlying credential) could be picked arbitrarily by that
     * resolution for an ordinary already-authenticated session, which expects the body-less
     * device-bound activation shape.
     */
    val deviceBound: Boolean
        get() = true
}
