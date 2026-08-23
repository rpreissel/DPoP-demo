package com.example.dpop.orchestrator.session

/**
 * Shared ACR ordering. The concrete amr->acr mapping used elsewhere (AuthPolicy.resolveAcr)
 * is a deliberately provisional placeholder (docs/04-orchestrierung.md #2); this object only
 * fixes the ordering of the known level names, not what earns them.
 */
object AcrLevels {
    /** Baseline floor when neither the channel nor a step-up process names one explicitly. */
    const val DEFAULT_REQUIRED_ACR = "loa2"

    private val order = listOf("none", "loa1", "loa2", "loa3")

    fun rank(acr: String?): Int = acr?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0

    fun max(a: String?, b: String?): String {
        if (a == null) return b ?: "none"
        if (b == null) return a
        return if (rank(a) >= rank(b)) a else b
    }

    fun min(a: String?, b: String?): String {
        if (a == null || b == null) return "none"
        return if (rank(a) <= rank(b)) a else b
    }
}
