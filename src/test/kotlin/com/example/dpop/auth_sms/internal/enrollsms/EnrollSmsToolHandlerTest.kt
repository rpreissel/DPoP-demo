package com.example.dpop.auth_sms.internal.enrollsms
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_api.AttributeAuthority
import com.example.dpop.tool_api.anchorRule
import com.example.dpop.tool_api.authority
import com.example.dpop.tool_spi.assertClaimsCovered
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the decision branches (invalid phone, wrong tan, ambiguous combinations) are
 * covered by [EnrollSmsFlowTest].
 */
class EnrollSmsToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollSmsToolSessionRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    // Explicit pepper so issue()/matches() stay reproducible within the test run.
    val tanGenerator = TanGenerator("test-pepper")
    val handler = EnrollSmsToolHandler(EnrollSmsDescriptor, toolDataRepository, enrollmentRepository, tanGenerator)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-sms tool session with no phone number yet") {
        val data = EnrollSmsToolSession(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("submitting a valid phone number") {
            val saved = slot<EnrollSmsToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it normalizes the number, issues a TAN, and asks for tanInput next") {
                val outcome = handler.patch(toolSessionId, phoneNumber = "+49 170 1234567", tan = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "tanInput"
                saved.captured.phoneNumber shouldBe "+491701234567"
                saved.captured.issuedTanHash.shouldNotBeNull()
            }
        }
    }

    given("an active enroll-sms tool session with a phone number and a valid, unexpired TAN") {
        val issued = tanGenerator.issue()
        val data = EnrollSmsToolSession(
            toolSessionId = toolSessionId,
            phoneNumber = "+491701234567",
            issuedTanHash = issued.hash,
            tanExpiresAt = issued.expiresAt
        )
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the correct TAN") {
            every { enrollmentRepository.save(any()) } answers { firstArg<AuthSmsEnrollment>().apply { id = 42L } }

            then("it enrolls the credential at the descriptor's own maxAcr and factorTypes") {
                val outcome = handler.patch(toolSessionId, phoneNumber = null, tan = issued.plainTan)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                val enrolled = outcome as ToolOutcome.Completed.Enrolled
                enrolled.enrollmentRef.type shouldBe "auth_sms.enrollment"
                enrolled.enrollmentRef.id shouldBe "42"
                enrolled.amr shouldBe listOf("sms")
                enrolled.achievedAcr shouldBe EnrollSmsDescriptor.maxAcr
                enrolled.factorTypes shouldBe EnrollSmsDescriptor.factorTypes
            }

            // The confirmed number is an assertion about the subject, so it reaches the account's
            // claim log (AccountService.recordClaims) - in its normalized form, not as typed.
            then("it asserts the confirmed number as a PHONE_NUMBER claim") {
                val outcome = handler.patch(toolSessionId, phoneNumber = null, tan = issued.plainTan)

                val enrolled = outcome as ToolOutcome.Completed.Enrolled
                enrolled.claims shouldBe listOf(
                    Claim(
                        AttributeType.PHONE_NUMBER,
                        "+491701234567",
                        ClaimSource.of(EnrollSmsDescriptor.toolId),
                        EnrollSmsDescriptor.maxAcr
                    )
                )
                // The same contract check JourneyService runs before adopting the outcome.
                assertClaimsCovered(EnrollSmsDescriptor, enrolled.claims)
            }
        }
    }

    // A phone number is not an anchor (AttributeAuthority.MethodModule), so nothing about this
    // claim asks for uniqueness: a family may legitimately share one number across accounts.
    given("the claim declaration") {
        then("it stays out of the anchor vocabulary entirely") {
            AttributeType.PHONE_NUMBER.anchorRule shouldBe null
            AttributeType.PHONE_NUMBER.authority shouldBe AttributeAuthority.MethodModule
        }
    }
})
