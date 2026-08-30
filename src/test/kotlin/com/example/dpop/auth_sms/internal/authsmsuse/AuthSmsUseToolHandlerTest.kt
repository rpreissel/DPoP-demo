package com.example.dpop.auth_sms.internal.authsmsuse
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment

import com.example.dpop.auth_sms.AuthSmsUseDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
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
 * wiring only - the tan-vs-state decision is covered by [AuthSmsUseFlowTest].
 */
class AuthSmsUseToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthSmsUseToolDataRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    val tanGenerator = TanGenerator("test-pepper")
    val handler = AuthSmsUseToolHandler(AuthSmsUseDescriptor, toolDataRepository, enrollmentRepository, tanGenerator)
    val toolSessionId = UUID.randomUUID()

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef("device_enrollment", "1"))
                }
            }
        }

        `when`("the referenced enrollment does not exist") {
            every { enrollmentRepository.findById(99L) } returns Optional.empty()

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "99"))
                }
            }
        }

        `when`("the referenced enrollment exists") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491701234567").apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)
            val saved = slot<AuthSmsUseToolData>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists a fresh TAN and asks for it at step auth") {
                val outcome = handler.start(toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "1"))

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "auth"
                saved.captured.issuedTanHash.shouldNotBeNull()
            }
        }
    }

    given("an active auth-sms tool session with a pending TAN") {
        val issued = tanGenerator.issue()
        val data = AuthSmsUseToolData(toolSessionId = toolSessionId, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the correct TAN") {
            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val outcome = handler.patch(toolSessionId, issued.plainTan)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                authenticated.amr shouldBe listOf("sms")
                authenticated.achievedAcr shouldBe AuthSmsUseDescriptor.maxAcr
                authenticated.factorTypes shouldBe AuthSmsUseDescriptor.factorTypes
            }
        }
    }
})
