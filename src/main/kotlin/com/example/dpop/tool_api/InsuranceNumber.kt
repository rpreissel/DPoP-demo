package com.example.dpop.tool_api

/**
 * A validated Versicherungsnummer: exactly eight digits. Only a person insured with us has one
 * (the Personenverzeichnis keeps it optional and changeable); when it exists it is also an account
 * anchor (`AttributeType.INSURANCE_NUMBER`). One place for the format, used by the Personenverzeichnis on
 * write and by the account on [normalizeAnchorValue].
 */
@JvmInline
value class InsuranceNumber private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^\\d{8}$".toRegex()

        /** Trims, then validates [raw] - `null` if it is not eight digits. */
        fun ofOrNull(raw: String): InsuranceNumber? = raw.trim().let { if (PATTERN.matches(it)) InsuranceNumber(it) else null }

        /** Same as [ofOrNull], but throws for a malformed value. */
        fun of(raw: String): InsuranceNumber = requireNotNull(ofOrNull(raw)) { "Invalid Versicherungsnummer: '$raw'" }
    }
}
