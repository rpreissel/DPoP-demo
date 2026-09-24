package com.example.dpop.account

/**
 * Who withdrew a claim, on whose authority - a retraction is itself an assertion and carries the
 * same provenance discipline as the claim it cancels (ADR-12, docs/12-entscheidungen.md).
 *
 * Deliberately NOT a [com.example.dpop.tool_spi.ClaimSource]: a tool can never retract
 * ([com.example.dpop.tool_spi.ToolOutcome] stays positive-only, ADR-12), so the two vocabularies
 * must not be interchangeable. Part of this module's API rather than its internals, because the
 * caller naming the anchor is always outside `account` (the orchestrator's account lifecycle).
 */
enum class RetractionAnchor {
    /** The account lifecycle itself, e.g. revoking the method instance that established the claim. */
    ACCOUNT_MANAGEMENT,

    /** The master-data backend no longer carries the value (e.g. a KVNR that was deregistered). */
    PERSON_DIRECTORY,

    /** A human operator, with a reason - the escape hatch for everything the two above do not cover. */
    OPERATOR
}
