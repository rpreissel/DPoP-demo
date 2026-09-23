package com.example.dpop.orchestrator.kernel

import com.example.dpop.tool_spi.AcrLevel

/**
 * Orchestrator-level ACR policy, plus the string-typed edge mirrors around it. The taxonomy
 * itself - level names, ordering, rank/min/max - moved to tool_spi's [AcrLevel] (closed
 * vocabulary both sides of the tool contract now speak, same role as FactorType); what stays
 * here is what only the orchestrator can decide: [DEFAULT_REQUIRED_ACR] (the session floor),
 * [HIGHEST] (the "no ceiling" value for a caller that deliberately does not want to cap an
 * MFA-combination bump) and [bump] (the MFA rule, docs/04-orchestrierung.md #2). The concrete
 * amr->acr mapping used elsewhere (AuthPolicy.resolveAcr) is a deliberately provisional
 * placeholder (docs/04-orchestrierung.md #2); this object only fixes the ordering of the
 * known level names, not what earns them.
 */
object AcrLevels {
    /**
     * Baseline floor when neither the channel nor a step-up process names one explicitly. Not
     * `const`: an [AcrLevel] value class can't be a compile-time constant the way the plain
     * `String` it replaced could - every former call site already only ever read this at
     * runtime, so nothing here actually depended on that.
     */
    val DEFAULT_REQUIRED_ACR = AcrLevel.LOA1

    /** The highest known level - the natural "no ceiling" value for a caller that deliberately does not want to cap an MFA-combination bump. */
    val HIGHEST: AcrLevel get() = AcrLevel(AcrLevel.KNOWN.last())

    /** Moves [acr] up by [steps] tiers, capped at the highest known level - used for the MFA bump (docs/04-orchestrierung.md #2). */
    fun bump(acr: AcrLevel, steps: Int = 1): AcrLevel =
        AcrLevel.levelAt((AcrLevel.rank(acr) + steps).coerceAtMost(AcrLevel.KNOWN.size - 1))

    // String-typed edge mirrors -------------------------------------------------------------------
    // Everything below merely re-exposes the typed taxonomy for the borders that still carry
    // plain strings (ChannelSession.acrFloor and AuthenticationMethod.enrolledUnderAcr JPA
    // columns, wire DTOs, Keycloak tokens). AcrLevel.of dampens unknown values to "none"
    // instead of passing them through: results are only ever rank-compared or written into
    // validated fields, and no caller ever fed a bogus value here.

    /** [AcrLevel.rank], for a raw string - null or unknown counts as rank 0. */
    fun rank(acr: String?): Int = AcrLevel.rank(AcrLevel.of(acr))

    /** [AcrLevel.levelAt], as the raw level name - "none" if out of range. */
    fun levelAt(rank: Int): String = AcrLevel.levelAt(rank).value

    /** [AcrLevel.max], for raw strings - a null side falls back to the other, both null -> "none". */
    fun max(a: String?, b: String?): String = AcrLevel.max(AcrLevel.of(a), AcrLevel.of(b)).value

    /** [AcrLevel.min], for raw strings - any null side means nothing was established -> "none". */
    fun min(a: String?, b: String?): String = AcrLevel.min(AcrLevel.of(a), AcrLevel.of(b)).value
}
