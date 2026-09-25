package com.example.dpop.auth_qr.internal.authqr

import com.example.dpop.texts.Text
import com.example.dpop.auth_qr.AuthQrDescriptor
import com.example.dpop.auth_qr.api.v1.QrPairingStep
import com.example.dpop.auth_qr.internal.QrLoginBrowserSide
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
    private val toolDataRepository: AuthQrToolSessionRepository,
    private val browserSide: QrLoginBrowserSide
) {

    @Transactional
    fun start(toolSessionId: UUID, accountId: Long): ToolOutcome {
        val pairingCode = browserSide.open(expectedAccountId = accountId)
        toolDataRepository.save(AuthQrToolSession(toolSessionId = toolSessionId, pairingCode = pairingCode))
        return waitingFor(pairingCode)
    }

    /**
     * Every browser PATCH: an empty poll while the app has not decided, then the confirmation code
     * the app shows ([QrLoginBrowserSide], review 2026-09 M-2).
     */
    @Transactional
    fun patch(toolSessionId: UUID, confirmationCode: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return when (val state = browserSide.advance(pairingCode, confirmationCode)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            is QrLoginBrowserSide.State.Confirmed ->
                if (state.accountId == state.expectedAccountId) {
                    ToolOutcome.Completed.Authenticated(
                        amr = listOf(descriptor.method),
                        achievedAcr = descriptor.maxAcr,
                        factorTypes = descriptor.factorTypes
                    )
                } else {
                    // A different account confirmed than the one this WEB session already knows -
                    // never silently take over (same reasoning as Action.RecordIdentification's account check).
                    ToolOutcome.Failed(Text("Bestätigung passt nicht zu diesem Konto"))
                }
            is QrLoginBrowserSide.State.Failed -> ToolOutcome.Failed(state.reason)
        }
    }

    /** Rebuilds the current step without deciding anything - an empty poll never writes. */
    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return if (browserSide.advance(pairingCode, null) == QrLoginBrowserSide.State.EnterCode) ENTER_CODE else waitingFor(pairingCode)
    }

    private fun waitingFor(pairingCode: String) =
        ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(pairingCode))

    private companion object {
        val ENTER_CODE = ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
    }
}
