package com.example.dpop.auth_qr.internal.authqr

import com.example.dpop.auth_qr.AuthQrDescriptor
import com.example.dpop.auth_qr.QR_LOGIN_TTL
import com.example.dpop.auth_qr.internal.PairingCodeGenerator
import com.example.dpop.auth_qr.internal.QrLoginRequest
import com.example.dpop.auth_qr.internal.QrLoginRequestRepository
import com.example.dpop.auth_qr.internal.QrLoginStatus
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * toolId=auth-qr: the account is already known via the channel (step-up/re-auth on a resolved
 * account) - approval must match [QrLoginRequest.expectedAccountId] exactly
 * (docs/04-orchestrierung.md; docs/05-api.md, Peer-Login bestätigen).
 *
 * Pure business logic; self-description lives in [AuthQrDescriptor].
 */
@Component
class AuthQrToolHandler(
    private val descriptor: AuthQrDescriptor,
    private val toolDataRepository: AuthQrToolDataRepository,
    private val qrLoginRequestRepository: QrLoginRequestRepository
) {

    @Transactional
    fun start(toolSessionId: UUID, accountId: Long): ToolOutcome {
        val pairingCode = PairingCodeGenerator.pairingCode()
        qrLoginRequestRepository.save(
            QrLoginRequest(
                pairingCode = pairingCode,
                verificationCode = PairingCodeGenerator.verificationCode(),
                expectedAccountId = accountId
            ).apply { expiresAt = Instant.now().plus(QR_LOGIN_TTL) }
        )
        toolDataRepository.save(AuthQrToolData(toolSessionId = toolSessionId, pairingCode = pairingCode))
        return outcomeFor(pairingCode)
    }

    /** Called on every poll (an empty PATCH) to re-check whether the APP side has decided yet. */
    @Transactional
    fun patch(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
            ?: return ToolOutcome.Failed("QR-Code abgelaufen")

        return when {
            request.status == QrLoginStatus.PENDING && Instant.now().isAfter(request.expiresAt) ->
                ToolOutcome.Failed("QR-Code abgelaufen")
            request.status == QrLoginStatus.PENDING -> outcomeFor(pairingCode)
            request.status == QrLoginStatus.APPROVED && request.resolvingAccountId == request.expectedAccountId ->
                ToolOutcome.Completed.Authenticated(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes
                )
            request.status == QrLoginStatus.APPROVED ->
                // A different account confirmed than the one this WEB session already knows -
                // never silently take over (same reasoning as ConfirmIdentity's account check).
                ToolOutcome.Failed("Bestätigung passt nicht zu diesem Konto")
            request.status == QrLoginStatus.DENIED -> ToolOutcome.Failed("Vom Nutzer abgelehnt")
            else -> ToolOutcome.Failed("QR-Code abgelaufen")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr tool session: $toolSessionId" }
        return outcomeFor(checkNotNull(data.pairingCode))
    }

    private fun outcomeFor(pairingCode: String): ToolOutcome.InProgress {
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
        return ToolOutcome.InProgress(
            nextStep = "waitForApp",
            data = mapOf("pairingCode" to pairingCode, "verificationCode" to request?.verificationCode)
        )
    }
}
