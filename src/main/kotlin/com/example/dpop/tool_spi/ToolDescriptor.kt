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
}
