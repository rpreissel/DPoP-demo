package com.example.dpop.orchestrator.session

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder

/**
 * Pure unit test of [AccountDeletionService]. The one behaviour worth pinning down here isn't
 * obvious from reading the happy path: [ChannelSession.authContextId] AND
 * [ChannelSession.authContext] (the mirrored, read-only navigation property for the same FK) must
 * BOTH be cleared before the referenced [AuthContext] row is deleted - leaving either one set is
 * exactly the bug that used to surface as a Hibernate `TransientPropertyValueException` on flush,
 * invisible to a plain field-by-field read of this class.
 */
class AccountDeletionServiceTest : BehaviorSpec({

    fun service(
        accountService: AccountService,
        cleanups: List<EnrollmentCleanup> = emptyList(),
        deviceAccountLinkRepository: DeviceAccountLinkRepository = mockk(relaxed = true),
        channelSessionRepository: ChannelSessionRepository = mockk(relaxed = true),
        authContextRepository: AuthContextRepository = mockk(relaxed = true)
    ) = AccountDeletionService(accountService, cleanups, deviceAccountLinkRepository, channelSessionRepository, authContextRepository)

    given("an account with channel sessions still bound to it") {
        then("every one of them is logged out with BOTH authContextId and authContext cleared, not just one") {
            val accountService = mockk<AccountService>(relaxed = true)
            every { accountService.allEnrollmentRefs(1L) } returns emptyList()
            val session = ChannelSession().apply {
                state = ChannelState.AUTHENTICATED
                authContextId = java.util.UUID.randomUUID()
                authContext = AuthContext(accountId = 1L)
            }
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByAccountId(1L) } returns listOf(session)
            every { channelSessionRepository.save(any<ChannelSession>()) } answers { firstArg() }

            service(accountService, channelSessionRepository = channelSessionRepository).deleteAccount(1L)

            session.state shouldBe ChannelState.LOGGED_OUT
            session.authContextId shouldBe null
            session.authContext shouldBe null
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
    }
})
