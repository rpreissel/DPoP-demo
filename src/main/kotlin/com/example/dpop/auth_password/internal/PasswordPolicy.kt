package com.example.dpop.auth_password.internal

/**
 * What a new password must satisfy - one rule for every way a password is set: the enroll-password
 * tool and the password change through Keycloak ([PasswordCredentialPortImpl.setNew], which used to
 * check nothing at all). Review 2026-09, Phase F.
 *
 * - at least [MIN_LENGTH] characters;
 * - at most [MAX_LENGTH] - long enough for any passphrase, short enough that nobody hashes megabytes;
 * - not one of the passwords every guessing attack tries first ([COMMON], compared case-insensitively).
 *   A curated list of the most frequent ones; a full breach corpus (e.g. a k-anonymity lookup) is a
 *   later step.
 */
internal object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    enum class Rejection { TOO_SHORT, TOO_LONG, TOO_COMMON }

    fun check(password: String): Rejection? = when {
        password.length < MIN_LENGTH -> Rejection.TOO_SHORT
        password.length > MAX_LENGTH -> Rejection.TOO_LONG
        password.lowercase() in COMMON -> Rejection.TOO_COMMON
        else -> null
    }

    /** What the user is told - the same wording wherever a password is set. */
    fun message(rejection: Rejection): String = when (rejection) {
        Rejection.TOO_SHORT -> "Passwort zu kurz (mindestens $MIN_LENGTH Zeichen)"
        Rejection.TOO_LONG -> "Passwort zu lang (höchstens $MAX_LENGTH Zeichen)"
        Rejection.TOO_COMMON -> "Dieses Passwort ist zu verbreitet - bitte ein anderes wählen"
    }

    private val COMMON: Set<String> = PasswordPolicy::class.java.getResourceAsStream("/auth_password/common-passwords.txt")
        .let { checkNotNull(it) { "common-passwords.txt missing" } }
        .bufferedReader().useLines { lines -> lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet() }
}
