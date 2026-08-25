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
