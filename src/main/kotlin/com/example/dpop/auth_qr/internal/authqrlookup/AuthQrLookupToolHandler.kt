package com.example.dpop.auth_qr.internal.authqrlookup

import com.example.dpop.texts.Text
import com.example.dpop.auth_qr.AuthQrLookupDescriptor
import com.example.dpop.auth_qr.api.v1.QrPairingStep
import com.example.dpop.auth_qr.internal.QrLoginBrowserSide
import com.example.dpop.tool_spi.MissingFields
import com.example.dpop.tool_spi.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
    private val toolDataRepository: AuthQrLookupToolSessionRepository,
    private val browserSide: QrLoginBrowserSide
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        val pairingCode = browserSide.open(expectedAccountId = null)
        toolDataRepository.save(AuthQrLookupToolSession(toolSessionId = toolSessionId, pairingCode = pairingCode))
        return waitingFor(pairingCode)
    }

    /**
     * Every browser PATCH: an empty poll while the app has not decided, then the confirmation code
     * the app shows ([QrLoginBrowserSide], review 2026-09 M-2). The account is whichever confirmed.
     */
    @Transactional
    fun patch(toolSessionId: UUID, confirmationCode: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return when (val state = browserSide.advance(pairingCode, confirmationCode)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            is QrLoginBrowserSide.State.Confirmed -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                accountId = state.accountId
            )
            // What is guessed here is the confirmation code, bounded by the pairing's own attempt
            // budget (review 2026-09 M-2) - not a secret of the account that approved.
            is QrLoginBrowserSide.State.Failed -> ToolOutcome.Failed.LookupAuth(state.reason, attemptedAccountId = null)
        }
    }

    /** Rebuilds the current step without deciding anything - an empty poll never writes. */
    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return if (browserSide.advance(pairingCode, null) == QrLoginBrowserSide.State.EnterCode) ENTER_CODE else waitingFor(pairingCode)
    }

    private fun waitingFor(pairingCode: String) =
        ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(pairingCode))

    private companion object {
        val ENTER_CODE = ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
    }
}
