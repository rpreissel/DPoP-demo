package com.example.dpop.auth_qr.internal.confirmqrlogin

import com.example.dpop.texts.Text
import com.example.dpop.auth_qr.ConfirmQrLoginDescriptor
import com.example.dpop.auth_qr.internal.PairingCodeGenerator
import com.example.dpop.auth_qr.internal.QrLoginRequestRepository
import com.example.dpop.auth_qr.internal.QrLoginStatus
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import com.example.dpop.auth_qr.api.v1.QrPairingStep
import com.example.dpop.tool_spi.MissingFields

/**
 * toolId=confirm-qr-login: approve or decline a pending `auth-qr`/`auth-qr-lookup` pairing
 * (docs/05-api.md, Peer-Login bestätigen; docs/07-betrieb.md #5). Three steps: `input` resolves the
 * pairing code the user typed or the deep link pre-filled, `confirm` takes the decision, and after an
 * approval `showCode` shows the confirmation code to type into the browser - approving alone logs
 * no browser in (review 2026-09, M-2). `done` ends it.
 *
 * Pure business logic; self-description lives in [ConfirmQrLoginDescriptor].
 */
@Component
class ConfirmQrLoginToolHandler(
    private val descriptor: ConfirmQrLoginDescriptor,
    private val toolDataRepository: ConfirmQrLoginToolSessionRepository,
    private val qrLoginRequestRepository: QrLoginRequestRepository
) {

    /**
     * [pairingCode] lets activation itself skip straight past the `input` step (docs/05-api.md,
     * Peer-Login bestätigen) when it is already known - e.g. the WEB channel's demo link
     * (docs/07-betrieb.md #5) pre-filled it before the app even chose this tool. Resolved through
     * the same [resolvePairingCode] the `input` step's own PATCH uses, so an unknown/expired code
     * falls back to `input` exactly like a rejected manual entry would, rather than failing activation.
     */
    @Transactional
    fun start(toolSessionId: UUID, pairingCode: String? = null): ToolOutcome {
        val data = ConfirmQrLoginToolSession(toolSessionId = toolSessionId)
        toolDataRepository.save(data)
        if (pairingCode.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        }
        return resolvePairingCode(data, pairingCode)
    }

    /**
     * [hasQrEnrollment] is resolved by the controller (this module may not depend on `account`) -
     * an account without an active `enroll-qr` opt-in must never approve a pairing on its behalf
     * (docs/03-tool-architektur.md).
     */
    @Transactional
    fun patch(toolSessionId: UUID, pairingCode: String?, decision: String?, accountId: Long, hasQrEnrollment: Boolean): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown confirm-qr-login tool session: $toolSessionId" }

        if (data.pairingCode == null) {
            return resolvePairingCode(data, pairingCode)
        }

        val resolvedCode = checkNotNull(data.pairingCode)
        // Already approved by this account: only `done` is left; the code was shown once and is gone.
        if (approvedBy(resolvedCode, accountId) && decision != DONE) return codeShownStep()
        return when (decision) {
            null -> confirmStepFor(resolvedCode)
            ACCEPT -> {
                if (!hasQrEnrollment) {
                    return ToolOutcome.Failed.NothingGuessed(Text("QR-Login ist für dieses Konto nicht aktiviert."))
                }
                val expectedAccountId = qrLoginRequestRepository.findByIdOrNull(resolvedCode)?.expectedAccountId
                if (expectedAccountId != null && expectedAccountId != accountId) {
                    // auth-qr (Step-up auf einem bereits bekannten WEB-Konto) kennt sein Zielkonto
                    // schon vorher - dann hier abbrechen statt scheinbar erfolgreich zu bestätigen
                    // und erst den WEB-Poll (AuthQrToolHandler) den Mismatch entdecken zu lassen.
                    // auth-qr-lookup setzt expectedAccountId bewusst nie, bleibt also unberührt.
                    return ToolOutcome.Failed.NothingGuessed(Text("Bestätigung passt nicht zu diesem Konto"))
                }
                val confirmationCode = PairingCodeGenerator.confirmationCode()
                val now = Instant.now()
                val rows = qrLoginRequestRepository.approveIfPending(
                    resolvedCode, accountId, PairingCodeGenerator.digest(confirmationCode), now, now.plus(CONFIRMATION_TTL)
                )
                if (rows == 1) {
                    // The one and only time the plaintext leaves the server - it is stored as a hash.
                    ToolOutcome.InProgress(nextStep = SHOW_CODE, stepData = QrPairingStep(confirmationCode = confirmationCode))
                } else {
                    ToolOutcome.Failed.NothingGuessed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
                }
            }
            DONE -> if (approvedBy(resolvedCode, accountId)) ToolOutcome.Completed.Approved() else confirmStepFor(resolvedCode)
            REJECT -> {
                val rows = qrLoginRequestRepository.denyIfPending(resolvedCode, Instant.now())
                if (rows == 1) {
                    ToolOutcome.Failed.NothingGuessed(Text("Vom Nutzer abgelehnt"))
                } else {
                    ToolOutcome.Failed.NothingGuessed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
                }
            }
            else -> error("Unbekannte decision: $decision")
        }
    }

    private fun resolvePairingCode(data: ConfirmQrLoginToolSession, pairingCode: String?): ToolOutcome {
        if (pairingCode.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        }
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
        if (request == null || request.status != QrLoginStatus.PENDING || Instant.now().isAfter(request.expiresAt)) {
            // Stays on `input` - an unknown/expired/already-decided code is retryable, not a
            // dead end (docs/05-api.md, Peer-Login bestätigen).
            return ToolOutcome.Failed.NothingGuessed(Text("Anfrage nicht gefunden oder abgelaufen"))
        }
        data.pairingCode = pairingCode
        toolDataRepository.save(data)
        return confirmStepFor(pairingCode)
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown confirm-qr-login tool session: $toolSessionId" }
        val pairingCode = data.pairingCode
            ?: return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        // This tool session only ever approves for its own channel's account - an approved request
        // here is its own approval.
        val approved = qrLoginRequestRepository.findByIdOrNull(pairingCode)
            ?.let { it.status == QrLoginStatus.APPROVED || it.status == QrLoginStatus.COMPLETED } ?: false
        return if (approved) codeShownStep() else confirmStepFor(pairingCode)
    }

    private fun confirmStepFor(pairingCode: String): ToolOutcome.InProgress =
        ToolOutcome.InProgress(nextStep = "confirm", stepData = MissingFields(listOf("decision")))

    /** After a reload: approved, but the code is not recoverable - only its hash is stored. */
    private fun codeShownStep(): ToolOutcome.InProgress =
        ToolOutcome.InProgress(nextStep = SHOW_CODE, stepData = MissingFields(listOf("decision")))

    private fun approvedBy(pairingCode: String, accountId: Long): Boolean =
        qrLoginRequestRepository.findByIdOrNull(pairingCode)?.let {
            it.resolvingAccountId == accountId && (it.status == QrLoginStatus.APPROVED || it.status == QrLoginStatus.COMPLETED)
        } ?: false

    companion object {
        const val ACCEPT = "accept"
        const val REJECT = "reject"
        const val DONE = "done"
        const val SHOW_CODE = "showCode"

        /** How long the browser has to type the code after the approval. */
        val CONFIRMATION_TTL: Duration = Duration.ofMinutes(2)
    }
}
