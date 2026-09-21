package com.example.dpop.kobil_mock

import com.example.dpop.kobil_mock.internal.SsmsAssertion
import com.example.dpop.kobil_mock.internal.SsmsAssertionRepository
import com.example.dpop.kobil_mock.internal.SsmsUser
import com.example.dpop.kobil_mock.internal.SsmsUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional

/**
 * The provider's own rules, with its repositories mocked - the properties that make the OTP detour
 * worth anything: an activation code is spent once, a wrong PIN produces no assertion, and a
 * one-time password is redeemable exactly once.
 */
class KobilSsmsTest : BehaviorSpec({

    fun fixture(user: SsmsUser): Triple<KobilSsms, SsmsUserRepository, MutableMap<String, SsmsAssertion>> {
        val users = mockk<SsmsUserRepository>(relaxed = true)
        val assertions = mockk<SsmsAssertionRepository>(relaxed = true)
        val store = mutableMapOf<String, SsmsAssertion>()

        every { users.findById(any()) } answers { Optional.ofNullable(if (firstArg<String>() == user.userId) user else null) }
        val saved = slot<SsmsAssertion>()
        every { assertions.save(capture(saved)) } answers { store[saved.captured.otp] = saved.captured; saved.captured }
        every { assertions.findById(any()) } answers { Optional.ofNullable(store[firstArg<String>()]) }

        return Triple(KobilSsms(users, assertions), users, store)
    }

    val tenant = "dpop-demo"

    given("a provisioned user with an activation code and a PIN") {
        then("activation binds a device and spends the code") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", activationCode = "ABC123")
            val (ssms, _, _) = fixture(user)
            val ref = KobilUserRef(tenant, "kob-1")

            val deviceId = ssms.activate(ref, "ABC123", "12345678")
            deviceId shouldNotBe ""
            ssms.deviceOf(ref) shouldBe deviceId

            // One activation code, one activation - as with any real activation secret.
            shouldThrow<KobilRejectedException> { ssms.activate(ref, "ABC123", "12345678") }
        }

        then("a wrong activation code binds nothing") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", activationCode = "ABC123")
            val (ssms, _, _) = fixture(user)
            shouldThrow<KobilRejectedException> { ssms.activate(KobilUserRef(tenant, "kob-1"), "WRONG", "12345678") }
            user.deviceId.shouldBeNull()
        }

        then("a user of another tenant is simply unknown") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", activationCode = "ABC123")
            val (ssms, _, _) = fixture(user)
            shouldThrow<KobilRejectedException> { ssms.activate(KobilUserRef("other", "kob-1"), "ABC123", "12345678") }
        }
    }

    given("an activated device") {
        then("a login files an assertion and hands back only the OTP that points at it") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", deviceId = "dev-1", riskSignals = "ROOTED")
            val (ssms, _, store) = fixture(user)
            val ref = KobilUserRef(tenant, "kob-1")

            val otp = ssms.login(ref, "12345678")
            store.keys shouldBe setOf(otp)

            val verification = ssms.verifyOtp(ref, otp)
            verification shouldBe KobilOtpVerification("dev-1", setOf(KobilRisk.ROOTED))

            // Redeemable exactly once, so a replayed reference buys nothing.
            ssms.verifyOtp(ref, otp).shouldBeNull()
        }

        then("a wrong PIN produces no assertion at all") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", deviceId = "dev-1")
            val (ssms, _, store) = fixture(user)
            shouldThrow<KobilRejectedException> { ssms.login(KobilUserRef(tenant, "kob-1"), "87654321") }
            store.isEmpty() shouldBe true
        }

        then("an OTP belonging to someone else is as good as unknown") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678", deviceId = "dev-1")
            val (ssms, _, _) = fixture(user)
            val ref = KobilUserRef(tenant, "kob-1")
            val otp = ssms.login(ref, "12345678")

            // Same tenant, different user: unknown, spent and foreign are deliberately one answer.
            val other = SsmsUser(userId = "kob-2", tenantId = tenant, pin = "12345678", deviceId = "dev-2")
            val (otherSsms, _, _) = fixture(other)
            otherSsms.verifyOtp(KobilUserRef(tenant, "kob-2"), otp).shouldBeNull()
        }
    }

    given("a user with no device yet") {
        then("a login cannot happen") {
            val user = SsmsUser(userId = "kob-1", tenantId = tenant, pin = "12345678")
            val (ssms, _, _) = fixture(user)
            shouldThrow<KobilRejectedException> { ssms.login(KobilUserRef(tenant, "kob-1"), "12345678") }
        }
    }
})
