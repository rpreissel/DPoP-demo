package com.example.dpop.tool_spi

/**
 * A validated Partnernummer: `P` and nine digits, canonical uppercase. The Personenverzeichnis
 * hands it out for every person it knows - Versicherter or Partner alike (ADR-34) - and it is the
 * person id everywhere else: the account's `PERSON_ID` anchor, the ID claims, Keycloak. One place
 * for the format, used by the Personenverzeichnis, by [Claim.validateValue] and by
 * `tool_api.normalizeAnchorValue`.
 */
@JvmInline
value class Partnernr private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^P\\d{9}$".toRegex()

        /** Normalizes (trim + uppercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): Partnernr? = raw.trim().uppercase().let { if (PATTERN.matches(it)) Partnernr(it) else null }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): Partnernr = requireNotNull(ofOrNull(raw)) { "Invalid Partnernr: '$raw'" }

        /** The Partnernummer with these nine digits - how the Personenverzeichnis mints a new one. */
        fun ofDigits(digits: Int): Partnernr = of("P%09d".format(digits))
    }
}
