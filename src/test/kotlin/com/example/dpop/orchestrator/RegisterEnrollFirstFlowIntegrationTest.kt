package com.example.dpop.orchestrator

import com.example.dpop.account.AccountProfile
import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * The REGISTER "Enrollment zuerst" experiment (docs/04-orchestrierung.md, `RegisterEnrollFirstStrategy`):
 * enrollment happens against an account with no person behind it yet, identification is offered
 * only at the very end, optionally. Toggled on for the whole suite via the admin endpoint
 * (`RegistrationOrderController`) - `IntegrationTestSupport`'s shared `beforeEach` resets
 * `orchestrator.feature_flag` too, so this never leaks into an ident-first test.
 */
class RegisterEnrollFirstFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeEach {
            stubDpopWithFakeJwk(jwkThumbprintService)
            put("/orchestrator/api/v1/admin/registration-order", """{"enrollFirst":true}""") shouldBe HttpStatus.OK
        }
    }

    /**
     * The account modules's own public API (`AccountService`), not raw SQL - type-safe, and never
     * reaches into `account.internal` from outside the module. Each of the tests below creates
     * exactly one account, so "the only one that exists right now" is unambiguous.
     */
    private fun theAccount(): AccountProfile =
        accountService.findAccount(accountService.allAccountIds().single())!!

    init {
        given("registration order set to enroll-first, a fresh channel, APP") {
            `when`("enrolling email, then sms, declining the closing identification offer") {
                then("finishes AUTHENTICATED with no person behind the account, enrolledUnderAcr stays loa1") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                // Mandatory order: email first, and the only candidate - single-candidate auto-skip
                // goes straight into the tool instead of a selectMethod screen.
                channelResponse.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")

                confirmEmail(channelSessionId)

                // Email done - SMS is mandatory next, still no identification step yet, same
                // single-candidate auto-skip.
                val afterEmail = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterEmail.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "enroll")

                enrollSms(channelSessionId)

                // Every obligation discharged - address attested, then the password, which is now
                // required on every channel. The account is still unidentified, so the optional
                // RE_IDENTIFY offer follows
                // (its own OfferReIdent prompt, "prompt"/"confirm" - the generic AnswerableState screen).
                enrollPassword(channelSessionId)
                val afterSms = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"

                val account = theAccount()
                account.personId.shouldBeNull()
                // The address is attested, not a method - the methods this run created are sms and
                // the mandatory password, both capped at what "Enrollment zuerst" ever proved.
                account.email.shouldNotBeNull()
                account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"

                }
            }
        }

        given("registration order set to enroll-first, a fresh channel, APP") {
            `when`("enrolling email, then sms, then accepting the closing identification offer via ident-fsc") {
                then("the account becomes identified, but the already-enrolled email keeps its original enrolledUnderAcr") {

                val channelSessionId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                get("/orchestrator/api/v1/channels/$channelSessionId").next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Two ident methods are registered (ident-fsc, ident-eid), so a selection page is
                // offered instead of a single-candidate skip (same as a fresh REGISTER's own start).
                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.next()["type"] shouldBe "orchestrator"
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val identified = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )
                identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val account = theAccount()
                account.personId.shouldNotBeNull()
                // Not retroactively upgraded - frozen at enrollment time, before any identification existed
                // (docs/04-orchestrierung.md, "IAL und AAL": the experiment makes this deliberately visible).
                // The address is attested, not a method - the methods this run created are sms and
                // the mandatory password, both capped at what "Enrollment zuerst" ever proved.
                account.email.shouldNotBeNull()
                account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"

                }
            }
        }

        given("an account already registered enroll-first with a real person, and a second, different account") {
            `when`("the second account's closing identification offer resolves to the SAME already-registered person") {
                then("it is rejected as a conflict, the second account stays unidentified") {

                // First account: enrolls, then actually identifies via ident-fsc.
                val firstChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(firstChannelId)
                enrollSms(firstChannelId)
                enrollPassword(firstChannelId)
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"accept"}""")
                val firstIdentToolSessionId = post("/orchestrator/api/v1/channels/$firstChannelId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$firstIdentToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                )

                // Second account: a fresh device/channel, enrolls independently, then tries to identify
                // as the SAME person (same KVNR/fsc) - a merge conflict, not supported.
                currentBindingKeyRef = "a-completely-different-binding-key"
                val secondChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(secondChannelId)
                enrollSms(secondChannelId)
                enrollPassword(secondChannelId)
                post("/orchestrator/api/v1/channels/$secondChannelId/answer", """{"answer":"accept"}""")
                val secondIdentToolSessionId = post("/orchestrator/api/v1/channels/$secondChannelId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch(
                        "/orchestrator/api/v1/tools/$secondIdentToolSessionId/ident-fsc",
                        """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
                    )
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                }
            }
        }

        given("an account already registered enroll-first and fully set up, on a different device") {
            `when`("confirming the SAME already-confirmed email address, with no other proof of that account") {
                then("it is rejected as a conflict, the second device never gets bound to the first account") {

                // First account, first device: confirms an email, enrolls sms+password - a real,
                // credentialed account with no person behind it (enroll-first never requires eID).
                val firstChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                val sharedEmail = confirmEmail(firstChannelId)
                enrollSms(firstChannelId)
                enrollPassword(firstChannelId)
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"decline"}""")
                val firstAccountId = accountService.allAccountIds().single()

                // A different device: fresh DPoP binding key, nothing shared with the first device
                // except knowledge of the same email address.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val secondChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                val secondNext = get("/orchestrator/api/v1/channels/$secondChannelId").nextRaw()
                val confirmToolSessionId = (secondNext["toolSessionId"] as? String)?.takeIf { secondNext["toolId"] == "confirm-email" }
                    ?: post("/orchestrator/api/v1/channels/$secondChannelId/tools/confirm-email").nextRaw()["toolSessionId"] as String
                val (code, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"email":"$sharedEmail"}""")
                }

                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"code":"$code"}""")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                // The first, already-credentialed account is untouched: still the only account,
                // still exactly the two methods the FIRST device enrolled.
                accountService.allAccountIds() shouldBe setOf(firstAccountId)
                accountService.findAccount(firstAccountId)!!.authenticationMethods.map { it.method }.toSet() shouldBe setOf("sms", "password")

                }
            }
        }

        given("a device already durably linked to somebody else's account, registering enroll-first") {
            `when`("the run finishes and the closing rebind prompt is declined") {
                then("the other account keeps both the device link and its device credential") {

                // Somebody else's account owns this physical device key.
                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                // The optional identification offer comes first - decline it.
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                // Only NOW is the rebind asked, which is the whole point: the enrollments above
                // deliberately did not take this device from the other account on their own.
                val rebindPrompt = get("/orchestrator/api/v1/channels/$channelSessionId")
                rebindPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.channel()["state"] shouldBe "AUTHENTICATED"

                linkedAccountId() shouldBe otherAccountId
                }
            }
        }

        given("a device already durably linked to somebody else's account, registering enroll-first") {
            `when`("the closing rebind prompt is accepted") {
                then("the device moves to the newly registered account") {

                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.channel()["state"] shouldBe "AUTHENTICATED"

                // The link moved off the seeded account onto the one this run created.
                val linked = linkedAccountId()
                linked shouldNotBe otherAccountId
                accountService.allAccountIds() shouldContain linked
                }
            }
        }
    }

    /** Which account this test's physical device key currently resolves to, if any. */
    private fun linkedAccountId(): Long? =
        jdbcTemplate.queryForList(
            "SELECT account_id FROM orchestrator.device_account_link WHERE binding_key_ref = ?",
            Long::class.java, currentBindingKeyRef
        ).firstOrNull()
}
