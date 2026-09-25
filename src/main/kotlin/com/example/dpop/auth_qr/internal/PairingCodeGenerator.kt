package com.example.dpop.auth_qr.internal

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The two codes of a QR login (docs/07-betrieb.md #5): [pairingCode] finds the waiting browser
 * request from the app (must resist guessing on its own), [confirmationCode] travels back from the
 * app to the browser (short enough to type; guessing is bounded by the request's attempt limit).
 */
internal object PairingCodeGenerator {
    /** Crockford-Base32-ish, no `I`/`L`/`O`/`U` - avoids characters a human misreads when copying by hand. */
    private const val PAIRING_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PAIRING_LENGTH = 8

    private val random = SecureRandom()

    fun pairingCode(): String =
        (1..PAIRING_LENGTH).map { PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)] }.joinToString("")

    /** Six digits - typed by hand into the browser; guessing is capped by [MAX_CONFIRMATION_ATTEMPTS]. */
    fun confirmationCode(): String = "%06d".format(random.nextInt(1_000_000))

    /** How the confirmation code is stored and compared - never in plaintext. */
    fun digest(code: String): String =
        MessageDigest.getInstance("SHA-256").digest(code.trim().toByteArray()).joinToString("") { "%02x".format(it) }

    /** Wrong confirmation codes a request survives before it is burned. */
    const val MAX_CONFIRMATION_ATTEMPTS = 3
}
