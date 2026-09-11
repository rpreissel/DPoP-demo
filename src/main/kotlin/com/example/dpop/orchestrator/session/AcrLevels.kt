package com.example.dpop.orchestrator.session

/**
 * Shared ACR ordering. The concrete amr->acr mapping used elsewhere (AuthPolicy.resolveAcr)
 * is a deliberately provisional placeholder (docs/04-orchestrierung.md #2); this object only
 * fixes the ordering of the known level names, not what earns them.
 */
object AcrLevels {
    /** Baseline floor when neither the channel nor a step-up process names one explicitly. */
    const val DEFAULT_REQUIRED_ACR = "loa1"

    private val order = listOf("none", "loa1", "loa2", "loa3")

    /** The highest known level - the natural "no ceiling" value for a caller that deliberately does not want to cap an MFA-combination bump. */
    val HIGHEST: String get() = order.last()

    fun rank(acr: String?): Int = acr?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0

    /** Inverse of [rank] - the level name at a given rank, "none" if out of range. */
    fun levelAt(rank: Int): String = order.getOrElse(rank) { "none" }

    fun max(a: String?, b: String?): String {
        if (a == null) return b ?: "none"
        if (b == null) return a
        return if (rank(a) >= rank(b)) a else b
    }

    fun min(a: String?, b: String?): String {
        if (a == null || b == null) return "none"
        return if (rank(a) <= rank(b)) a else b
    }

    /** Moves [acr] up by [steps] tiers, capped at the highest known level - used for the MFA bump (docs/04-orchestrierung.md #2). */
    fun bump(acr: String, steps: Int = 1): String =
        order.getOrElse((rank(acr) + steps).coerceAtMost(order.size - 1)) { order.last() }
}

/**
 * Nominal wrapper for one acr level name, ordered per [AcrLevels]. A `value class`, not a
 * typealias: erased to a plain String at runtime (zero cost), but unlike a typealias the
 * compiler now rejects a level string being passed where e.g. a method name was expected -
 * something [AcrLevels]' own bare-String functions can't prevent at their call sites.
 */
@JvmInline
value class AcrLevel(val value: String) : Comparable<AcrLevel> {
    override fun compareTo(other: AcrLevel): Int = AcrLevels.rank(value).compareTo(AcrLevels.rank(other.value))
    override fun toString(): String = value
}
