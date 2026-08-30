package com.example.dpop.auth_password.internal.authpasswordlookup
import com.example.dpop.auth_password.internal.PasswordHasher
import com.example.dpop.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.dpop.auth_password.internal.AuthPasswordEnrollment

import com.example.dpop.auth_password.AuthPasswordLookupDescriptor
import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the completeness decision is covered by [AuthPasswordLookupFlowTest].
 */
class AuthPasswordLookupToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthPasswordLookupToolDataRepository>()
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository>()
    val handler = AuthPasswordLookupToolHandler(AuthPasswordLookupDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()

    given("an active auth-password-lookup tool session") {
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(AuthPasswordLookupToolData(toolSessionId = toolSessionId))

        `when`("email and password resolve to an active, matching enrollment") {
            val enrollment = AuthPasswordEnrollment(passwordHash = PasswordHasher.hash("hunter2")).apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)

            then("it authenticates for that account") {
                val outcome = handler.patch(
                    toolSessionId, email = "max@example.com", password = "hunter2",
                    accountId = 42L, enrollmentRef = EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1")
                )

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                authenticated.accountId shouldBe 42L
                authenticated.amr shouldBe listOf("password")
            }
        }

        `when`("the email never resolved to anything (enumeration protection)") {
            then("it fails with the same constant-shape message, naming no account") {
                val outcome = handler.patch(toolSessionId, email = "unknown@example.com", password = "hunter2", accountId = null, enrollmentRef = null)

                outcome shouldBe ToolOutcome.Failed("E-Mail oder Passwort ungueltig", attemptedAccountId = null)
            }
        }
    }
})
