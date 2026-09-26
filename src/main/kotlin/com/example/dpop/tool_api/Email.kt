package com.example.dpop.tool_api

/**
 * A validated, normalized email address (docs/ideen/account-attribute-und-trust-vereinheitlichen.md,
 * `DPoP-demo-4vd.5`) - the one place the format rule and the normalization rule (trim, lowercase)
 * both live. Replaces `ConfirmEmailFlow`'s own local `EMAIL_PATTERN` (same regex, now shared) and
 * backs [normalizeAnchorValue]'s `EMAIL` case, so `resolveByAnchor`/`anchorValue`/
 * `resolveAccountByEmail` and the `account.anchor` write path all agree on exactly one definition
 * of "a well-formed email", not three ad hoc ones.
 */
@JvmInline
value class Email private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".toRegex()

        /**
         * The longest address SMTP can deliver to (RFC 5321: a 256-octet path minus its angle
         * brackets). Longer is no address - and would otherwise reach the 255-character columns and
         * fail there as an internal error (review 2026-09-26, F-10).
         */
        private const val MAX_LENGTH = 254

        /** Normalizes (trim + lowercase) then validates [raw] - `null` if it is not well-formed. */
        fun ofOrNull(raw: String): Email? {
            val normalized = raw.trim().lowercase()
            return if (normalized.length <= MAX_LENGTH && PATTERN.matches(normalized)) Email(normalized) else null
        }

        /** Same as [ofOrNull], but throws for a malformed address - the anchor write/lookup path's contract. */
        fun of(raw: String): Email = requireNotNull(ofOrNull(raw)) { "Invalid email: '$raw'" }
    }
}
