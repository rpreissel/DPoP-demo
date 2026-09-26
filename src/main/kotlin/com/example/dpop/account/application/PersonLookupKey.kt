package com.example.dpop.account.application

import com.example.dpop.account.domain.passportForm
import com.example.dpop.account.infrastructure.ChangeLogRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A search key and the id of the secret it was computed with. */
data class LookupKey(val keyId: String, val value: String)

/**
 * Secrets that wrote older change log entries (review 2026-09-26, F-10): `keyId -> secret`, only
 * for searching. A rotated-out secret stays here until no entry carries its id any more.
 */
@ConfigurationProperties(prefix = "account.change-log")
data class PreviousLookupSecrets(val previousLookupSecrets: Map<String, String> = emptyMap())

/**
 * The change log's search key for a person (ADR-39): a keyed hash of name, first name and date of
 * birth - the three things a person can still tell us years after their account was deleted.
 *
 * Keyed, not a plain hash: name plus date of birth has so little entropy that a plain hash could be
 * reversed by trying a list of common names against every date of the last hundred years. Without
 * the secret nobody can compute a key, so the column reveals nothing on its own.
 *
 * Rotation (review 2026-09-26, F-10): every key is written with the current secret and its id
 * (`account.change-log.lookup-key-id`); a search tries the current and every previous secret
 * (`account.change-log.previous-lookup-secrets.<id>`). A key cannot be recomputed with a new secret
 * - the names are not stored - so an old secret must stay until the retention has deleted the last
 * entry with its id; [LookupKeyCoverageCheck] refuses to run with one missing.
 *
 * The names go through [passportForm], the same spelling rule the attestation check uses.
 */
@Component
class PersonLookupKey(
    @Value("\${account.change-log.lookup-secret:}") secret: String,
    @Value("\${account.change-log.lookup-key-id:1}") private val currentKeyId: String,
    previous: PreviousLookupSecrets,
    @Value("\${demo.mode:true}") demoMode: Boolean,
) {
    private val current: SecretKeySpec
    private val previous: Map<String, SecretKeySpec> =
        previous.previousLookupSecrets.mapValues { (_, value) -> SecretKeySpec(value.toByteArray(), ALGORITHM) }

    init {
        val effective = secret.ifBlank {
            // Outside demo mode a missing secret is a configuration error, never a silent default:
            // keys computed with a publicly known secret would be reversible by anyone.
            check(demoMode) { "account.change-log.lookup-secret (CHANGE_LOG_LOOKUP_SECRET) must be set outside demo mode" }
            LoggerFactory.getLogger(PersonLookupKey::class.java)
                .warn("account.change-log.lookup-secret not set - using the demo secret (demo mode only)")
            DEMO_SECRET
        }
        current = SecretKeySpec(effective.toByteArray(), ALGORITHM)
        check(currentKeyId !in this.previous) { "account.change-log.lookup-key-id '$currentKeyId' is also listed among the previous secrets" }
    }

    /** Every key id a search can match - the current one and each previous one. */
    val knownKeyIds: Set<String> get() = previous.keys + currentKeyId

    /** The key to write, with the current secret. `null` unless all three are known - a partial key would match far too many people. */
    fun of(name: String?, vorname: String?, geburtsdatum: LocalDate?): LookupKey? =
        input(name, vorname, geburtsdatum)?.let { LookupKey(currentKeyId, hmac(current, it)) }

    /** The keys to search for: the same person under the current and every previous secret. */
    fun candidates(name: String?, vorname: String?, geburtsdatum: LocalDate?): List<String> {
        val input = input(name, vorname, geburtsdatum) ?: return emptyList()
        return (previous.values + current).map { hmac(it, input) }
    }

    private fun input(name: String?, vorname: String?, geburtsdatum: LocalDate?): String? {
        if (name.isNullOrBlank() || vorname.isNullOrBlank() || geburtsdatum == null) return null
        return listOf(passportForm(name), passportForm(vorname), geburtsdatum.toString()).joinToString("\u001F")
    }

    private fun hmac(key: SecretKeySpec, input: String): String =
        HexFormat.of().formatHex(Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(input.toByteArray()))

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val DEMO_SECRET = "demo-only-change-log-lookup-secret"
    }
}

/**
 * Refuses to run while the change log holds keys no configured secret can match (review
 * 2026-09-26, F-10) - a rotated-out secret removed too early would make every older entry
 * unsearchable, silently. Outside demo mode the start fails; in demo mode it warns.
 */
@Component
class LookupKeyCoverageCheck(
    private val repository: ChangeLogRepository,
    private val personLookupKey: PersonLookupKey,
    @Value("\${demo.mode:true}") private val demoMode: Boolean,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun check() {
        val orphaned = repository.lookupKeyIds() - personLookupKey.knownKeyIds
        if (orphaned.isEmpty()) return
        val message = "account.change_log holds search keys with id(s) $orphaned, but no secret for them is configured " +
            "(account.change-log.previous-lookup-secrets) - those entries cannot be found by name"
        check(demoMode) { message }
        LoggerFactory.getLogger(LookupKeyCoverageCheck::class.java).warn(message)
    }
}
