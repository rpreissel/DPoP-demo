package com.example.dpop.auth_qr.internal

import com.example.dpop.auth_qr.QR_LOGIN_TTL
import com.example.dpop.texts.Text
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The browser's half of a QR login, shared by `auth-qr` and `auth-qr-lookup` - they differ only in
 * whether the account is known beforehand, not in how the pairing runs (docs/07-betrieb.md #5):
 *
 * 1. `waitForApp`: the browser shows the pairing code and polls until the app decides.
 * 2. `enterCode`: the app approved and shows a confirmation code; the browser must type it.
 * 3. Only the right code, in time, completes the request - once.
 *
 * Step 2 is the point (review 2026-09, M-2). If approving in the app logged the browser in
 * directly, a victim approving an attacker's pairing code from a link would hand over the account.
 * With step 2 the victim would also have to type a code into the attacker's browser.
 */
@Component
class QrLoginBrowserSide(
    private val requests: QrLoginRequestRepository,
    private val confirmationCodeDigest: ConfirmationCodeDigest,
) {

    sealed interface State {
        data object WaitingForApp : State
        data object EnterCode : State
        /** [expectedAccountId] as opened - `auth-qr` must see exactly that account confirm. */
        data class Confirmed(val accountId: Long, val expectedAccountId: Long?) : State
        data class Failed(val reason: Text) : State
    }

    /** Opens a new request; returns its pairing code. [expectedAccountId] only for `auth-qr`. */
    @Transactional
    fun open(expectedAccountId: Long?): String {
        val pairingCode = PairingCodeGenerator.pairingCode()
        requests.save(
            QrLoginRequest(pairingCode = pairingCode, expectedAccountId = expectedAccountId)
                .apply { expiresAt = Instant.now().plus(QR_LOGIN_TTL) }
        )
        return pairingCode
    }

    /** Each browser PATCH: an empty poll, or - once approved - the typed [confirmationCode]. */
    @Transactional
    fun advance(pairingCode: String, confirmationCode: String?): State {
        val request = requests.findByIdOrNull(pairingCode) ?: return State.Failed(EXPIRED)
        val now = Instant.now()
        val expired = request.expiresAt?.let { now.isAfter(it) } ?: true
        return when (request.status) {
            QrLoginStatus.PENDING -> if (expired) State.Failed(EXPIRED) else State.WaitingForApp
            QrLoginStatus.APPROVED -> when {
                expired -> State.Failed(EXPIRED)
                confirmationCode.isNullOrBlank() -> State.EnterCode
                requests.completeIfConfirmed(pairingCode, confirmationCodeDigest.of(confirmationCode), now) == 1 ->
                    State.Confirmed(
                        checkNotNull(request.resolvingAccountId) { "APPROVED QrLoginRequest without resolvingAccountId" },
                        request.expectedAccountId
                    )
                else -> {
                    requests.countWrongConfirmation(pairingCode, PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS)
                    val burned = request.confirmationAttempts + 1 >= PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS
                    State.Failed(if (burned) BURNED else WRONG_CODE)
                }
            }
            // Completed once already - a replayed code must not log in a second browser.
            QrLoginStatus.COMPLETED -> State.Failed(ALREADY_USED)
            QrLoginStatus.DENIED -> State.Failed(Text("Vom Nutzer abgelehnt"))
            QrLoginStatus.EXPIRED -> State.Failed(EXPIRED)
        }
    }

    private companion object {
        val EXPIRED = Text("QR-Code abgelaufen")
        val WRONG_CODE = Text("Bestätigungscode falsch")
        val BURNED = Text("Zu viele falsche Bestätigungscodes. Bitte starten Sie die Anmeldung per QR-Code neu.")
        val ALREADY_USED = Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen")
    }
}
