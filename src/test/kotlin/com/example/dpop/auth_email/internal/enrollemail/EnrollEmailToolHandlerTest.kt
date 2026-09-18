package com.example.dpop.auth_email.internal.enrollemail

import com.example.dpop.auth_email.EnrollEmailDescriptor
import com.example.dpop.auth_email.internal.EmailCodeGenerator
import com.example.dpop.tool_api.AccountDirectory
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
 * covered by [EnrollEmailFlowTest].
 */
class EnrollEmailToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollEmailToolDataRepository>()
    val accountDirectory = mockk<AccountDirectory>()
    val emailCodeGenerator = EmailCodeGenerator("test-pepper")
    val handler = EnrollEmailToolHandler(EnrollEmailDescriptor, toolDataRepository, accountDirectory, emailCodeGenerator)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-email tool session with no email yet") {
        val data = EnrollEmailToolData(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("submitting an email that is not yet taken") {
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "max@example.com") } returns null
            val saved = slot<EnrollEmailToolData>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists the address and a fresh code, asking for codeInput") {
                val outcome = handler.patch(toolSessionId, email = "max@example.com", code = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "codeInput"
                saved.captured.email shouldBe "max@example.com"
                saved.captured.issuedCodeHash.shouldNotBeNull()
            }
        }

        `when`("submitting an email that is already taken") {
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "taken@example.com") } returns 42L

            then("it fails without ever touching account state") {
                val outcome = handler.patch(toolSessionId, email = "taken@example.com", code = null)

                outcome shouldBe ToolOutcome.Failed("E-Mail-Adresse bereits vergeben")
            }
        }
    }

    given("an active enroll-email tool session with a pending code") {
        val issued = emailCodeGenerator.issue()
        val data = EnrollEmailToolData(toolSessionId = toolSessionId, email = "max@example.com", issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

            `when`("confirming with the correct code") {
                then("it enrolls with the anchor as the durable reference and a typed EMAIL claim") {
                    val outcome = handler.patch(toolSessionId, email = null, code = issued.plainCode)

                    outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                    (outcome as ToolOutcome.Completed.Enrolled).enrollmentRef shouldBe EMAIL_ANCHOR_ENROLLMENT
                    outcome.auditDetails shouldBe null
                    outcome.claims shouldBe listOf(
                        Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(EnrollEmailDescriptor.toolId), EnrollEmailDescriptor.maxAcr)
                    )
                }
            }
    }
})
