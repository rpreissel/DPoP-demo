package com.example.dpop.tool_spi

/**
 * One of the four known acr levels ("none", "loa1".."loa3") - the closed taxonomy both sides of
 * the tool contract speak: a [ToolDescriptor] declares how high it can possibly get
 * ([ToolDescriptor.maxAcr]), a completed run reports what it actually achieved
 * ([ToolOutcome.Completed.achievedAcr]). Ordering and comparisons live in the companion, so
 * the taxonomy is defined exactly once, in the same "closed vocabulary, both sides of the
 * contract" role as [FactorType].
 *
 * A `value class`, not a typealias: erased to a plain String at runtime (zero cost), but
 * unlike a typealias the compiler rejects a level string being passed where e.g. a method
 * name was expected. Deliberately NOT an enum either: raw level strings still arrive from
 * untyped borders (Keycloak tokens, JPA columns, wire DTOs), and an enum's `valueOf` would
 * turn one unknown value into a crash - [of] dampens it to [NONE] instead (unknown or
 * absent -> rank 0), while the init check below still catches in-process typos at
 * construction time.
 */
@JvmInline
value class AcrLevel(val value: String) : Comparable<AcrLevel> {
    init {
        require(value in KNOWN) { "Unknown acr level: $value (known: $KNOWN)" }
    }

    override fun compareTo(other: AcrLevel): Int = rank(this).compareTo(rank(other))

    override fun toString(): String = value

    companion object {
        /** All known levels, lowest first - the single ordering everything below derives from. */
        val KNOWN = listOf("none", "loa1", "loa2", "loa3")

        /** Nothing established - an anonymous channel, and what [of] dampens anything unknown to. */
        val NONE = AcrLevel("none")

        /** One factor proven, e.g. an SMS code or a password on its own. */
        val LOA1 = AcrLevel("loa1")

        /**
         * Two distinct factors of distinct [FactorType]s, or one tool that reaches it alone
         * (`auth-device`, `ident-fsc`). The gate for anything that changes the account itself -
         * managing methods, confirming a peer login, deleting the account.
         */
        val LOA2 = AcrLevel("loa2")

        /** The highest level; no tool in this demo reaches it, so it only ever appears as a target. */
        val LOA3 = AcrLevel("loa3")

        /**
         * Defensive border constructor: an untyped raw value (null, blank, or a level some
         * Keycloak token made up) becomes [NONE] rather than throwing - the taxonomy may only
         * reject what in-process code could have prevented; border readers go through this.
         */
        fun of(raw: String?): AcrLevel = if (raw != null && raw in KNOWN) AcrLevel(raw) else NONE

        /**
         * A level a CLIENT asked for (`requiredAcr`) - an unknown one is the caller's mistake, a 400
         * in words it can act on, not the internal construction check above.
         */
        fun requested(raw: String): AcrLevel =
            if (raw in KNOWN) AcrLevel(raw)
            else throw InvalidInputException(com.example.dpop.texts.Text("Unbekanntes Sicherheitsniveau '{acr}' - bekannt sind {known}", "acr" to raw, "known" to KNOWN.joinToString()))

        /** Position in [KNOWN]; `null` counts as "nothing established" (rank 0, like [NONE]). */
        fun rank(acr: AcrLevel?): Int = acr?.let { KNOWN.indexOf(it.value) }?.takeIf { it >= 0 } ?: 0

        /** Inverse of [rank] - the level at a given rank, [NONE] if out of range. */
        fun levelAt(rank: Int): AcrLevel = KNOWN.getOrNull(rank)?.let(::AcrLevel) ?: NONE

        /** The higher of two levels; a `null` argument falls back to the other, both `null` -> [NONE]. */
        fun max(a: AcrLevel?, b: AcrLevel?): AcrLevel {
            if (a == null) return b ?: NONE
            if (b == null) return a
            return if (a >= b) a else b
        }

        /** The lower of two levels; any `null` argument means nothing was established -> [NONE]. */
        fun min(a: AcrLevel?, b: AcrLevel?): AcrLevel {
            if (a == null || b == null) return NONE
            return if (a <= b) a else b
        }
    }
}
