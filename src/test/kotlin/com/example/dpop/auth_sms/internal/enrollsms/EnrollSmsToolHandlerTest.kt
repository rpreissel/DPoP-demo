package com.example.dpop.auth_sms.internal.enrollsms
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.tool_spi.ToolOutcome
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

    val toolDataRepository = mockk<EnrollSmsToolDataRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    // Explicit pepper so issue()/matches() stay reproducible within the test run.
    val tanGenerator = TanGenerator("test-pepper")
    val handler = EnrollSmsToolHandler(EnrollSmsDescriptor, toolDataRepository, enrollmentRepository, tanGenerator)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-sms tool session with no phone number yet") {
        val data = EnrollSmsToolData(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("submitting a valid phone number") {
            val saved = slot<EnrollSmsToolData>()
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
        val data = EnrollSmsToolData(
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
                enrolled.enrollmentRef.type shouldBe "auth_sms_enrollment"
                enrolled.enrollmentRef.id shouldBe "42"
                enrolled.amr shouldBe listOf("sms")
                enrolled.achievedAcr shouldBe EnrollSmsDescriptor.maxAcr
                enrolled.factorTypes shouldBe EnrollSmsDescriptor.factorTypes
            }
        }
    }
})
