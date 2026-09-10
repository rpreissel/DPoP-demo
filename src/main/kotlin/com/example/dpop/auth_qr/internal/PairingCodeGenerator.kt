package com.example.dpop.auth_qr.internal

import java.security.SecureRandom

/**
 * Two very differently-sized codes for two very different jobs (docs/ideen/qr-login-ueber-app.md
 * #6): [pairingCode] is the actual access-control token (must resist guessing), [verificationCode]
 * is only ever eyeballed side by side on two screens (guessing it unlocks nothing).
 */
internal object PairingCodeGenerator {
    /** Crockford-Base32-ish, no `I`/`L`/`O`/`U` - avoids characters a human misreads when copying by hand. */
    private const val PAIRING_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PAIRING_LENGTH = 8

    private val random = SecureRandom()

    fun pairingCode(): String =
        (1..PAIRING_LENGTH).map { PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)] }.joinToString("")

    /** 000-999 - deliberately low entropy, see this object's own doc. */
    fun verificationCode(): String = "%03d".format(random.nextInt(1000))
}
