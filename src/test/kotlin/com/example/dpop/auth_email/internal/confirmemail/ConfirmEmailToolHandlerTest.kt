package com.example.dpop.auth_email.internal.confirmemail

import com.example.dpop.auth_email.ConfirmEmailDescriptor
import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_api.EMAIL_ANCHOR_ENROLLMENT
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the decision branches (invalid email, wrong code, ambiguous combinations) are
 * covered by [ConfirmEmailFlowTest].
 */
class ConfirmEmailToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<ConfirmEmailToolSessionRepository>()
    val emailCodeGenerator = EmailCodeGenerator("test-pepper")
    val handler = ConfirmEmailToolHandler(ConfirmEmailDescriptor, toolDataRepository, emailCodeGenerator)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-email tool session with no email yet") {
        val data = ConfirmEmailToolSession(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("submitting an email") {
            val saved = slot<ConfirmEmailToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists the address and a fresh code, asking for codeInput") {
                val outcome = handler.patch(toolSessionId, email = "max@example.com", code = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "codeInput"
                saved.captured.email shouldBe "max@example.com"
                saved.captured.issuedCodeHash.shouldNotBeNull()
            }
        }

    }

    given("an active enroll-email tool session with a pending code") {
        val issued = emailCodeGenerator.issue()
        val data = ConfirmEmailToolSession(toolSessionId = toolSessionId, email = "max@example.com", issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

            `when`("confirming with the correct code") {
                then("it attests the address without creating a credential") {
                    val outcome = handler.patch(toolSessionId, email = null, code = issued.plainCode)

                    outcome.shouldBeInstanceOf<ToolOutcome.Completed.Attested>()
                    (outcome as ToolOutcome.Completed.Attested).claims shouldBe listOf(
                        Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ConfirmEmailDescriptor.toolId), ConfirmEmailDescriptor.maxAcr)
                    )
                    // No amr and no factor: confirming an address is not an authentication proof,
                    // so it must not raise the channel's assurance.
                    outcome.amr shouldBe emptyList()
                    outcome.factorTypes shouldBe emptySet()
                    outcome.auditDetails shouldBe null
                }
            }
    }
})
