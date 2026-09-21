package com.example.dpop.tool_api

/**
 * A validated, normalized Krankenversichertennummer (docs/ideen/account-attribute-und-trust-
 * vereinheitlichen.md, `DPoP-demo-4vd.5`): one letter followed by nine digits, canonical
 * uppercase form - the shape `IdentFscToolController`'s OpenAPI example (`A123456789`) shows,
 * enforced at runtime. Backs [AttributeRules.normalizeAnchorValue]'s `KVNR` case. Safe to enforce strictly there: a KVNR
 * only ever reaches a [com.example.dpop.tool_spi.Claim] after `PersonDirectory.findPersonIdByKvnr`
 * already resolved it to a real person (`IdentFscToolHandler`/`IdentEidToolHandler`), so a
 * malformed value never gets this far in practice.
 */
@JvmInline
value class Kvnr private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[A-Z]\\d{9}$".toRegex()

        /** Normalizes (trim + uppercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): Kvnr? {
            val normalized = raw.trim().uppercase()
            return if (PATTERN.matches(normalized)) Kvnr(normalized) else null
        }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): Kvnr = requireNotNull(ofOrNull(raw)) { "Invalid Kvnr: '$raw'" }
    }
}
