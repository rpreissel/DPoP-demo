package com.example.dpop.auth_qr.internal.authqrlookup

import com.example.dpop.auth_qr.AuthQrLookupDescriptor
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
 * toolId=auth-qr-lookup: the WEB channel does not know the account yet - it is revealed by
 * whichever account approves via `confirm-qr-login` (docs/04-orchestrierung.md; docs/05-api.md, Peer-Login bestätigen), a
 * passwordless "log in with your phone".
 *
 * Pure business logic; self-description lives in [AuthQrLookupDescriptor].
 */
@Component
class AuthQrLookupToolHandler(
    private val descriptor: AuthQrLookupDescriptor,
    private val toolDataRepository: AuthQrLookupToolDataRepository,
    private val qrLoginRequestRepository: QrLoginRequestRepository
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        val pairingCode = PairingCodeGenerator.pairingCode()
        qrLoginRequestRepository.save(
            QrLoginRequest(
                pairingCode = pairingCode,
                verificationCode = PairingCodeGenerator.verificationCode(),
                expectedAccountId = null
            ).apply { expiresAt = Instant.now().plus(QR_LOGIN_TTL) }
        )
        toolDataRepository.save(AuthQrLookupToolData(toolSessionId = toolSessionId, pairingCode = pairingCode))
        return outcomeFor(pairingCode)
    }

    /** Called on every poll (an empty PATCH) to re-check whether the APP side has decided yet. */
    @Transactional
    fun patch(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
            ?: return ToolOutcome.Failed("QR-Code abgelaufen")

        return when {
            request.status == QrLoginStatus.PENDING && Instant.now().isAfter(request.expiresAt) ->
                ToolOutcome.Failed("QR-Code abgelaufen")
            request.status == QrLoginStatus.PENDING -> outcomeFor(pairingCode)
            request.status == QrLoginStatus.APPROVED ->
                ToolOutcome.Completed.Authenticated(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    accountId = checkNotNull(request.resolvingAccountId) { "APPROVED QrLoginRequest without resolvingAccountId" }
                )
            request.status == QrLoginStatus.DENIED -> ToolOutcome.Failed("Vom Nutzer abgelehnt")
            else -> ToolOutcome.Failed("QR-Code abgelaufen")
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
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
