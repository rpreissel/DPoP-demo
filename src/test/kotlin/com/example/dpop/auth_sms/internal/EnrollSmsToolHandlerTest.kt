package com.example.dpop.auth_sms.internal

import com.example.dpop.auth_sms.EnrollSmsDescriptor
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.assertions.throwables.shouldThrow
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
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers the business-rule
 * branches (phone validation, TAN matching) that used to only be reachable through a full HTTP
 * round-trip in an integration test.
 */
class EnrollSmsToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollSmsToolDataRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    val handler = EnrollSmsToolHandler(EnrollSmsDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-sms tool session with no phone number yet") {
        val data = EnrollSmsToolData(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("submitting a phone number in an unrecognizable format") {
            then("it throws IllegalArgumentException, never persisting a TAN") {
                shouldThrow<IllegalArgumentException> {
                    handler.patch(toolSessionId, phoneNumber = "not-a-number", tan = null)
                }
                verify(exactly = 0) { toolDataRepository.save(any()) }
            }
        }

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
        val issued = TanGenerator.issue()
        val data = EnrollSmsToolData(
            toolSessionId = toolSessionId,
            phoneNumber = "+491701234567",
            issuedTanHash = issued.hash,
            tanExpiresAt = issued.expiresAt
        )
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the wrong TAN") {
            then("it fails without enrolling anything") {
                val outcome = handler.patch(toolSessionId, phoneNumber = null, tan = "000000")

                outcome shouldBe ToolOutcome.Failed("TAN ungueltig oder abgelaufen")
                verify(exactly = 0) { enrollmentRepository.save(any()) }
            }
        }

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
