package com.example.dpop.auth_sms.internal.authsmslookup
import com.example.dpop.auth_sms.internal.TanGenerator
import com.example.dpop.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.dpop.auth_sms.internal.AuthSmsEnrollment

import com.example.dpop.auth_sms.AuthSmsLookupDescriptor
import com.example.dpop.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolOutcome
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
 * wiring only - the tan-vs-state decision is covered by [AuthSmsLookupFlowTest].
 */
class AuthSmsLookupToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthSmsLookupToolDataRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    val tanGenerator = TanGenerator("test-pepper")
    val handler = AuthSmsLookupToolHandler(AuthSmsLookupDescriptor, toolDataRepository, enrollmentRepository, tanGenerator)
    val toolSessionId = UUID.randomUUID()

    given("an active auth-sms-lookup tool session") {
        val data = AuthSmsLookupToolData(toolSessionId = toolSessionId)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("the submitted email resolves to an account with an active sms method") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491701234567").apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)
            val saved = slot<AuthSmsLookupToolData>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists the resolved account and a fresh TAN, revealing the demo TAN") {
                val outcome = handler.submitEmail(toolSessionId, accountId = 42L, enrollmentRef = EnrollmentRef(SMS_ENROLLMENT_TYPE, "1"))

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "tanInput"
                outcome.data?.get("demo").shouldNotBeNull()
                saved.captured.accountId shouldBe 42L
                saved.captured.issuedTanHash.shouldNotBeNull()
            }
        }

        `when`("the submitted email does not resolve to anything (enumeration protection)") {
            val saved = slot<AuthSmsLookupToolData>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it still issues a TAN, but reveals no demo TAN and stores no account") {
                val outcome = handler.submitEmail(toolSessionId, accountId = null, enrollmentRef = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                (outcome as ToolOutcome.InProgress).nextStep shouldBe "tanInput"
                outcome.data?.get("demo") shouldBe null
                saved.captured.accountId shouldBe null
            }
        }
    }

    given("a resolved account with a pending TAN") {
        val issued = tanGenerator.issue()
        val data = AuthSmsLookupToolData(toolSessionId = toolSessionId, accountId = 42L, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the correct TAN") {
            then("it authenticates for that account") {
                val outcome = handler.patch(toolSessionId, issued.plainTan)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                val authenticated = outcome as ToolOutcome.Completed.Authenticated
                authenticated.accountId shouldBe 42L
                authenticated.amr shouldBe listOf("sms")
            }
        }
    }
})
