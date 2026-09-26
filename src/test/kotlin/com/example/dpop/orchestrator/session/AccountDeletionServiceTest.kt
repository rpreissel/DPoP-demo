package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.journeytrace.JourneyTraceRepository
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder

/**
 * Pure unit test of [AccountDeletionService]. The one behaviour worth pinning down here isn't
 * obvious from reading the happy path: [ChannelSession.authContextId]/[ChannelSession.
 * authEvidenceId] AND their mirrored, read-only navigation properties ([ChannelSession.
 * authContext]/[ChannelSession.authEvidence], same FKs) must ALL be cleared before the referenced
 * [AuthContext]/[AuthEvidence] rows are deleted - leaving any one of them set is exactly the bug
 * that used to surface as a Hibernate `TransientPropertyValueException` on flush, invisible to a
 * plain field-by-field read of this class.
 */
class AccountDeletionServiceTest : BehaviorSpec({

    fun service(
        accountService: AccountService,
        cleanups: List<EnrollmentCleanup> = emptyList(),
        deviceAccountLinkRepository: DeviceAccountLinkRepository = mockk(relaxed = true),
        channelSessionRepository: ChannelSessionRepository = mockk(relaxed = true),
        authContextRepository: AuthContextRepository = mockk(relaxed = true),
        authEvidenceRepository: AuthEvidenceRepository = mockk(relaxed = true),
        journeyTraceRepository: JourneyTraceRepository = mockk(relaxed = true),
        attemptThrottleRepository: AttemptThrottleRepository = mockk(relaxed = true)
    ) = AccountDeletionService(
        accountService,
        cleanups,
        deviceAccountLinkRepository,
        channelSessionRepository,
        authContextRepository,
        authEvidenceRepository,
        journeyTraceRepository,
        attemptThrottleRepository
    )

    given("an account with channel sessions still bound to it") {
        then("every one of them is logged out with BOTH authContextId/authEvidenceId and their navigation properties cleared, not just one") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns emptyList()
            val session = ChannelSession().apply {
                state = ChannelState.AUTHENTICATED
                authContextId = java.util.UUID.randomUUID()
                authContext = AuthContext(accountId = 1L)
                authEvidenceId = java.util.UUID.randomUUID()
                authEvidence = AuthEvidence(accountId = 1L)
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByAccountId(1L) } returns listOf(session)
            every { channelSessionRepository.save(any<ChannelSession>()) } answers { firstArg() }

            service(accountService, channelSessionRepository = channelSessionRepository).deleteAccount(1L)

            session.state shouldBe ChannelState.LOGGED_OUT
            session.authContextId shouldBe null
            session.authContext shouldBe null
            session.authEvidenceId shouldBe null
            session.authEvidence shouldBe null
            verify { channelSessionRepository.save(session) }
        }
    }

    given("an account with enrollment refs across different method modules") {
        then("each ref is dispatched to the cleanup matching its own type, never a different module's") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns listOf(
                EnrollmentRef("sms", "sms-ref"), EnrollmentRef("password", "password-ref")
            )
            val smsCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { smsCleanup.enrollmentType } returns "sms"
            val passwordCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { passwordCleanup.enrollmentType } returns "password"

            service(accountService, cleanups = listOf(smsCleanup, passwordCleanup)).deleteAccount(1L)

            verify { smsCleanup.delete(EnrollmentRef("sms", "sms-ref")) }
            verify { passwordCleanup.delete(EnrollmentRef("password", "password-ref")) }
            verify(exactly = 0) { smsCleanup.delete(EnrollmentRef("password", "password-ref")) }
        }

        then("a ref whose type no registered module claims is skipped, not a crash") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns listOf(EnrollmentRef("unknown-method", "ref"))

            service(accountService, cleanups = emptyList()).deleteAccount(1L)
        }
    }

    given("revokeMethod - a single credential, not the whole account") {
        then("deletes only that instance's own enrollment and deactivates it, leaving the account row untouched") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.enrollmentRefFor(1L, "method-instance-1") } returns EnrollmentRef("device", "device-ref")
            val deviceCleanup = mockk<EnrollmentCleanup>(relaxed = true)
            every { deviceCleanup.enrollmentType } returns "device"

            service(accountService, cleanups = listOf(deviceCleanup)).revokeMethod(1L, "method-instance-1")

            verify { deviceCleanup.delete(EnrollmentRef("device", "device-ref")) }
            verify { accountService.deactivateAuthenticationMethod(1L, "method-instance-1") }
            verify(exactly = 0) { accountService.deleteAccount(any()) }
        }

        then("a method with no resolvable enrollmentRef is still deactivated, just without a cleanup call") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.enrollmentRefFor(1L, "method-instance-1") } returns null

            service(accountService).revokeMethod(1L, "method-instance-1")

            verify { accountService.deactivateAuthenticationMethod(1L, "method-instance-1") }
        }
    }

    given("the full deletion") {
        then("removes cross-module credentials and the device link before the account row itself, never after") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns emptyList()
            val deviceAccountLinkRepository = mockk<DeviceAccountLinkRepository>(relaxed = true)

            service(accountService, deviceAccountLinkRepository = deviceAccountLinkRepository).deleteAccount(1L)

            verifyOrder {
                deviceAccountLinkRepository.deleteByAccountId(1L)
                accountService.deleteAccount(1L)
            }
        }

        then("erases the journey trace by account AND by the account's channel sessions, plus the account-keyed throttle counters (A5)") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns emptyList()
            val channelSessionId = java.util.UUID.randomUUID()
            val session = ChannelSession().apply { this.channelSessionId = channelSessionId }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByAccountId(1L) } returns listOf(session)
            every { channelSessionRepository.save(any()) } returns session
            val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
            val attemptThrottleRepository = mockk<AttemptThrottleRepository>(relaxed = true)

            service(
                accountService,
                channelSessionRepository = channelSessionRepository,
                journeyTraceRepository = journeyTraceRepository,
                attemptThrottleRepository = attemptThrottleRepository
            ).deleteAccount(1L)

            verify { journeyTraceRepository.deleteByAccountIdOrChannelSessionIdIn(1L, listOf(channelSessionId)) }
            verify {
                attemptThrottleRepository.deleteBySubjectAndScopeIn(
                    "1",
                    listOf(ThrottleScope.ACCOUNT, ThrottleScope.ACCOUNT_SEND)
                )
            }
        }

        then("leaves the throttle scopes that are not account-keyed alone - deletion must not become a way to reset someone else's budget") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns emptyList()
            val attemptThrottleRepository = mockk<AttemptThrottleRepository>(relaxed = true)
            val scopes = slot<Collection<ThrottleScope>>()
            every { attemptThrottleRepository.deleteBySubjectAndScopeIn(any(), capture(scopes)) } returns 0

            service(accountService, attemptThrottleRepository = attemptThrottleRepository).deleteAccount(1L)

            scopes.captured shouldNotContain ThrottleScope.PERSON
            scopes.captured shouldNotContain ThrottleScope.BINDING_KEY
            scopes.captured shouldNotContain ThrottleScope.CONTACT_SEND
        }
    }
})
