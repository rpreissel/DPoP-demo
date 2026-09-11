package com.example.dpop.auth_qr.internal.confirmqrlogin

import com.example.dpop.auth_qr.ConfirmQrLoginDescriptor
import com.example.dpop.auth_qr.internal.QrLoginRequestRepository
import com.example.dpop.auth_qr.internal.QrLoginStatus
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * toolId=confirm-qr-login: approve or decline a pending `auth-qr`/`auth-qr-lookup` pairing
 * (docs/05-api.md, Peer-Login bestätigen; docs/07-betrieb.md #5). Two steps: `input` resolves the pairing code the user
 * typed or the deep link pre-filled, `confirm` shows the verification code and takes the decision.
 *
 * Pure business logic; self-description lives in [ConfirmQrLoginDescriptor].
 */
@Component
class ConfirmQrLoginToolHandler(
    private val descriptor: ConfirmQrLoginDescriptor,
    private val toolDataRepository: ConfirmQrLoginToolDataRepository,
    private val qrLoginRequestRepository: QrLoginRequestRepository
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(ConfirmQrLoginToolData(toolSessionId = toolSessionId))
        return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to listOf("pairingCode")))
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
        return when (decision) {
            null -> confirmStepFor(resolvedCode)
            ACCEPT -> {
                if (!hasQrEnrollment) {
                    return ToolOutcome.Failed("QR-Login ist für dieses Konto nicht aktiviert.")
                }
                val rows = qrLoginRequestRepository.resolveIfPending(resolvedCode, QrLoginStatus.APPROVED, accountId)
                if (rows == 1) {
                    ToolOutcome.Completed.Approved()
                } else {
                    ToolOutcome.Failed("Anfrage wurde bereits bearbeitet oder ist abgelaufen")
                }
            }
            REJECT -> {
                val rows = qrLoginRequestRepository.resolveIfPending(resolvedCode, QrLoginStatus.DENIED, null)
                if (rows == 1) {
                    ToolOutcome.Failed("Vom Nutzer abgelehnt")
                } else {
                    ToolOutcome.Failed("Anfrage wurde bereits bearbeitet oder ist abgelaufen")
                }
            }
            else -> throw IllegalArgumentException("Unbekannte decision: $decision")
        }
    }

    private fun resolvePairingCode(data: ConfirmQrLoginToolData, pairingCode: String?): ToolOutcome {
        if (pairingCode.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to listOf("pairingCode")))
        }
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
        if (request == null || request.status != QrLoginStatus.PENDING || Instant.now().isAfter(request.expiresAt)) {
            // Stays on `input` - an unknown/expired/already-decided code is retryable, not a
            // dead end (docs/05-api.md, Peer-Login bestätigen).
            return ToolOutcome.Failed("Anfrage nicht gefunden oder abgelaufen")
        }
        data.pairingCode = pairingCode
        toolDataRepository.save(data)
        return confirmStepFor(pairingCode)
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown confirm-qr-login tool session: $toolSessionId" }
        return data.pairingCode?.let { confirmStepFor(it) }
            ?: ToolOutcome.InProgress(nextStep = "input", data = mapOf("missingFields" to listOf("pairingCode")))
    }

    private fun confirmStepFor(pairingCode: String): ToolOutcome.InProgress {
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
        return ToolOutcome.InProgress(nextStep = "confirm", data = mapOf("verificationCode" to request?.verificationCode))
    }

    companion object {
        const val ACCEPT = "accept"
        const val REJECT = "reject"
    }
}
