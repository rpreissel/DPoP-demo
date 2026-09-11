package com.example.dpop.auth_qr.internal.confirmqrlogin

import com.example.dpop.auth_qr.ConfirmQrLoginDescriptor
import com.example.dpop.auth_qr.internal.QrLoginRequest
import com.example.dpop.auth_qr.internal.QrLoginRequestRepository
import com.example.dpop.auth_qr.internal.QrLoginStatus
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers the ACCEPT-side
 * expectedAccountId guard (a `confirm-qr-login` must fail fast in the app itself when it can never
 * satisfy the WEB side's own `auth-qr` step-up - see [com.example.dpop.auth_qr.internal.authqr.AuthQrToolHandler]'s
 * matching late check) plus the pre-existing accept/reject wiring.
 */
class ConfirmQrLoginToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<ConfirmQrLoginToolDataRepository>()
    val qrLoginRequestRepository = mockk<QrLoginRequestRepository>()
    val handler = ConfirmQrLoginToolHandler(ConfirmQrLoginDescriptor, toolDataRepository, qrLoginRequestRepository)
    val toolSessionId = UUID.randomUUID()
    val pairingCode = "ABCD1234"

    given("a confirm-qr-login tool session that already resolved its pairing code") {
        val data = ConfirmQrLoginToolData(toolSessionId = toolSessionId).apply { this.pairingCode = pairingCode }
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("the pairing's expectedAccountId belongs to a DIFFERENT account than the one confirming") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, verificationCode = "123456", expectedAccountId = 42L)
            )

            then("it fails immediately, never reaching resolveIfPending") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome shouldBe ToolOutcome.Failed("Bestätigung passt nicht zu diesem Konto")
            }
        }

        `when`("the pairing's expectedAccountId matches the confirming account") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, verificationCode = "123456", expectedAccountId = 99L)
            )
            every { qrLoginRequestRepository.resolveIfPending(pairingCode, QrLoginStatus.APPROVED, 99L) } returns 1

            then("it approves") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome.shouldBeApproved()
            }
        }

        `when`("expectedAccountId is null (auth-qr-lookup, no target account to violate)") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, verificationCode = "123456", expectedAccountId = null)
            )
            every { qrLoginRequestRepository.resolveIfPending(pairingCode, QrLoginStatus.APPROVED, 99L) } returns 1

            then("it approves - any account may confirm") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome.shouldBeApproved()
            }
        }

        `when`("the account has no active qr enrollment at all") {
            then("it fails before even looking at expectedAccountId") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = false)

                outcome shouldBe ToolOutcome.Failed("QR-Login ist für dieses Konto nicht aktiviert.")
            }
        }
    }
})

private fun ToolOutcome.shouldBeApproved() {
    this shouldBe ToolOutcome.Completed.Approved()
}
