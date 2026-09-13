package com.example.dpop.orchestrator.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * Rate limit on TAN/code SENDS, either to one account (once a LOOKUP_AUTH tool has resolved the
 * submitted address) or to one raw contact address (for self-service enrollment, where no account
 * may exist yet - see [ThrottleScope.CONTACT_SEND]).
 *
 * `auth-sms-lookup`/`auth-email-lookup`/`enroll-sms`/`enroll-email` all (re-)send a fresh
 * TAN/code on every PATCH that carries a phone number/email, regardless of what happened before -
 * that is the whole point of their "resubmitting restarts the flow" behaviour. Nothing else bounds
 * how often that happens: a wrong-guess counter like [LoginThrottleService] never fires here
 * (submitting an address is never itself a wrong guess), and [ChannelCreationThrottleService] only
 * bounds how many `ChannelSession`s a binding key can open, not how many times an already-open one
 * resubmits an address. Without this service, any of the four tools above is a free SMS/mail bomb
 * against any contact address the caller happens to know.
 *
 * Counted per rolling window (every resolved send attempt counts, not just failures). For the
 * account-keyed variant, callers must fold a throttled result into the ordinary "unknown address"
 * path (see `ToolEndpoint.isLockedOut`) rather than a distinct error - a distinguishable throttle
 * there would leak account existence exactly like an unfolded lockout would. The contact-keyed
 * variant has no such constraint (the caller picked the address themselves) and may surface it
 * directly.
 */
@Service
@Transactional
class SendThrottleService(private val counter: AttemptCounter) {

    fun isThrottled(accountId: Long): Boolean =
        !counter.recordWindowedAttempt(ThrottleScope.ACCOUNT_SEND, accountId.toString(), MAX_PER_WINDOW, WINDOW)

    /** [contact] should already be normalized (trimmed/lowercased) by the caller's own validation. */
    fun isThrottledForContact(contact: String): Boolean =
        !counter.recordWindowedAttempt(ThrottleScope.CONTACT_SEND, hash(contact), MAX_PER_WINDOW, WINDOW)

    // Hashed rather than stored raw: unlike ACCOUNT_SEND's numeric id, a contact address is PII,
    // and the throttle subject only ever needs to prove "same address as before", not the value
    // itself. Also sidesteps the subject column's 128-char limit for arbitrarily long addresses.
    private fun hash(contact: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(contact.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        private const val MAX_PER_WINDOW = 3
        private val WINDOW: Duration = Duration.ofMinutes(10)
    }
}
