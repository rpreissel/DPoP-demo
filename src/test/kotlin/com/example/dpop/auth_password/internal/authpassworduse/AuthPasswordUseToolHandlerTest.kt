package com.example.dpop.auth_password.internal.authpassworduse
import com.example.dpop.auth_password.internal.PasswordHasher
import com.example.dpop.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.dpop.auth_password.internal.AuthPasswordEnrollment

import com.example.dpop.auth_password.AuthPasswordUseDescriptor
import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the input decision is covered by [AuthPasswordUseFlowTest].
 */
class AuthPasswordUseToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthPasswordUseToolDataRepository>()
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository>()
    val handler = AuthPasswordUseToolHandler(AuthPasswordUseDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> {
                    handler.start(toolSessionId, EnrollmentRef("device_enrollment", "1"))
                }
            }
        }

        `when`("the referenced enrollment exists") {
            every { enrollmentRepository.existsById(1L) } returns true
            every { toolDataRepository.save(any()) } answers { firstArg() }

            then("it asks for the password at step auth") {
                val outcome = handler.start(toolSessionId, EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1"))

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "auth"
            }
        }
    }

    given("an active auth-password tool session bound to an enrollment") {
        val enrollment = AuthPasswordEnrollment(passwordHash = PasswordHasher.hash("hunter2")).apply { id = 1L }
        val data = AuthPasswordUseToolData(toolSessionId = toolSessionId, enrollmentRefType = PASSWORD_ENROLLMENT_TYPE, enrollmentRefId = "1")
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)
        every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)

        `when`("submitting the correct password") {
            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val outcome = handler.patch(toolSessionId, "hunter2")

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                authenticated.amr shouldBe listOf("password")
                authenticated.achievedAcr shouldBe AuthPasswordUseDescriptor.maxAcr
            }
        }

        `when`("submitting the wrong password") {
            then("it fails") {
                handler.patch(toolSessionId, "wrong") shouldBe ToolOutcome.Failed("Passwort ungueltig")
            }
        }
    }
})
