package com.example.dpop.account.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The change log's search key for a person (ADR-39): a keyed hash of name, first name and date of
 * birth - the three things a person can still tell us years after their account was deleted.
 *
 * Keyed, not a plain hash: name plus date of birth has so little entropy that a plain hash could be
 * reversed by trying a list of common names against every date of the last hundred years. Without
 * the secret nobody can compute a key, so the column reveals nothing on its own. The secret must
 * therefore be kept as long as the change log itself (`account.change-log.retention-years`) -
 * rotating it makes every older key unsearchable.
 *
 * The names go through [passportForm], the same spelling rule the attestation check uses.
 */
@Component
class PersonLookupKey(
    @Value("\${account.change-log.lookup-secret:}") secret: String,
    @Value("\${demo.mode:true}") demoMode: Boolean,
) {
    private val key: SecretKeySpec

    init {
        val effective = secret.ifBlank {
            // Outside demo mode a missing secret is a configuration error, never a silent default:
            // keys computed with a publicly known secret would be reversible by anyone.
            check(demoMode) { "account.change-log.lookup-secret (CHANGE_LOG_LOOKUP_SECRET) must be set outside demo mode" }
            LoggerFactory.getLogger(PersonLookupKey::class.java)
                .warn("account.change-log.lookup-secret not set - using the demo secret (demo mode only)")
            DEMO_SECRET
        }
        key = SecretKeySpec(effective.toByteArray(), ALGORITHM)
    }

    /** `null` unless all three are known - a partial key would match far too many people. */
    fun of(name: String?, vorname: String?, geburtsdatum: LocalDate?): String? {
        if (name.isNullOrBlank() || vorname.isNullOrBlank() || geburtsdatum == null) return null
        val input = listOf(passportForm(name), passportForm(vorname), geburtsdatum.toString()).joinToString("\u001F")
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }
        return HexFormat.of().formatHex(mac.doFinal(input.toByteArray()))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val DEMO_SECRET = "demo-only-change-log-lookup-secret"
    }
}
