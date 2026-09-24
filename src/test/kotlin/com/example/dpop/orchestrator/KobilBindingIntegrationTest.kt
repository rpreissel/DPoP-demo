package com.example.dpop.orchestrator

import com.example.dpop.texts.templateOf
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * enroll-kobil / auth-kobil end to end over HTTP, including the app's own detour through the
 * provider: this test plays the MC SDK by calling the `kobil_mock` endpoints directly, the way a
 * phone would. That is what makes the central property observable - the one-time password travels
 * through the client, the assertion behind it never does.
 *
 * Nothing about KOBIL is stubbed. The only mocks are the channel's DPoP proof and the thumbprint
 * service, the same pair every integration suite here mocks, because a test cannot present a real
 * channel key.
 *
 * Not covered here: that the credential is invisible from another installation. That rests on
 * `ToolDescriptor.keyBinding`, whose rule `KobilDescriptorsTest` pins directly and whose journey
 * behaviour `MultiDeviceCredentialIntegrationTest` already exercises for the same mechanism.
 */
class KobilBindingIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    /** What the app ends up holding after a setup: the SDK's addressing data plus its local secret. */
    private data class EnrolledDevice(val tenantId: String, val kobilUserId: String, val unlockSecret: String)

    private fun enrollKobil(channelSessionId: String, biometricConsent: Boolean = true): EnrolledDevice {
        val activated = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-kobil")
        val toolSessionId = activated.nextRaw()["toolSessionId"] as String
        val stepData = activated.stepData()

        val device = EnrolledDevice(
            tenantId = stepData["tenantId"] as String,
            kobilUserId = stepData["kobilUserId"] as String,
            unlockSecret = stepData["unlockSecret"] as String,
        )

        // The SDK's activation - straight to the provider, not through our backend.
        post(
            "/mock-kobil/activate",
            """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","activationCode":"${stepData["activationCode"]}","pin":"${stepData["pin"]}"}"""
        )

        patch(
            "/orchestrator/api/v1/tools/$toolSessionId/enroll-kobil",
            """{"activated":true,"biometricConsent":$biometricConsent,"label":"Testhandy"}"""
        )
        return device
    }

    private fun startAuth(channelSessionId: String): String {
        val started = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-kobil")
        started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "unlock")
        return started.nextRaw()["toolSessionId"] as String
    }

    private fun releasePin(toolSessionId: String, unlock: String): Map<String, Any?> =
        post("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil/pin-releases", """{"unlock":$unlock}""")

    private fun biometric(device: EnrolledDevice) = """{"kind":"biometric","unlockSecret":"${device.unlockSecret}"}"""

    /** The SDK's login: the app gets a one-time password, nothing else. */
    private fun sdkLogin(device: EnrolledDevice, pin: String): String = post(
        "/mock-kobil/login",
        """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","pin":"$pin"}"""
    )["otp"] as String

    private fun redeem(toolSessionId: String, otp: String): Map<String, Any?> =
        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil", """{"otp":"$otp"}""")

    /** The methods whose key-bound credential lives on THIS device, as device-link lists them. */
    @Suppress("UNCHECKED_CAST")
    private fun boundMethods(): List<String> =
        (get("/orchestrator/api/v1/app/channels/device-link")["boundCredentials"] as List<Map<String, Any?>>)
            .map { it["method"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun activeMethodId(channelSessionId: String, method: String): String =
        (get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>)
            .first { it["method"] == method }["id"] as String

    private fun passwordEnrolledChannel(): String {
        val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
        enrollPassword(channelSessionId)
        return channelSessionId
    }

    /** Enrolls on one channel and returns the device plus a fresh, unauthenticated channel to log in on. */
    private fun enrolledDeviceOnFreshChannel(biometricConsent: Boolean = true): Pair<EnrolledDevice, String> {
        val enrollChannel = passwordEnrolledChannel()
        val device = enrollKobil(enrollChannel, biometricConsent)
        val loginChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        return device to loginChannel
    }

    init {
        given("an identified channel with a confirmed address") {
            then("enroll-kobil hands out the activation data, PIN included") {
                val channelSessionId = passwordEnrolledChannel()
                val activated = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-kobil")

                activated.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-kobil", "step" to "activate")
                val stepData = activated.stepData()
                stepData.keys shouldContain "activationCode"
                // One of exactly two points in a credential's life where the design says the PIN
                // may leave the backend: the SDK's activation call takes it.
                stepData.keys shouldContain "pin"
                stepData.keys shouldContain "unlockSecret"
            }

            then("the confirmed activation becomes an active method and reaches loa2 in one run") {
                val channelSessionId = passwordEnrolledChannel()
                enrollKobil(channelSessionId)

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()
                channel["currentAcr"] shouldBe "loa2"
                (channel["currentAmr"] as List<*>).shouldContainAll("kobil", "biometric")
                (channel["activeMethods"] as List<*>).methodNames() shouldContain "kobil"
            }

            then("a confirmation before the device has activated changes nothing - it is not a failed attempt") {
                val channelSessionId = passwordEnrolledChannel()
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-kobil")
                    .nextRaw()["toolSessionId"] as String

                val unchanged = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/enroll-kobil",
                    """{"activated":true,"biometricConsent":true}"""
                )
                unchanged.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-kobil", "step" to "activate")
            }
        }

        given("an enrolled KOBIL credential and a fresh channel") {
            then("the provider's own binding is readable from the device link - and vanishes with the credential") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()

                // The identifier KOBIL assigned, which the client cannot learn any other way: it
                // never travels through an assertion the client gets to see.
                boundMethods() shouldContain "kobil"

                // Removing it needs a session that has proven loa2 - this one has, through kobil.
                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String
                redeem(toolSessionId, sdkLogin(device, pin))
                delete("/orchestrator/api/v1/channels/$loginChannel/methods/${activeMethodId(loginChannel, "kobil")}")

                // Gone with the credential - and that absence is exactly the signal the client
                // uses to drop its locally stored unlock secret (AppChannelApp's device-link
                // effect): a secret for a binding that no longer exists.
                boundMethods() shouldNotContain "kobil"
            }

            then("the biometric unlock authenticates and reaches loa2") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)

                val released = releasePin(toolSessionId, biometric(device))
                released.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "otp")
                val pin = released.stepData()["kobilPin"] as String?
                pin.shouldNotBeNull()

                val authenticated = redeem(toolSessionId, sdkLogin(device, pin))
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$loginChannel").channel()
                channel["currentAcr"] shouldBe "loa2"
                (channel["currentAmr"] as List<*>).shouldContainAll("kobil", "biometric")
            }

            then("the password unlock reports pin, never password - otherwise the account password would count twice") {
                val enrollChannel = passwordEnrolledChannel()
                val device = enrollKobil(enrollChannel)
                val loginChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, """{"kind":"password","password":"correct-horse-battery"}""")
                    .stepData()["kobilPin"] as String
                redeem(toolSessionId, sdkLogin(device, pin))

                val amr = get("/orchestrator/api/v1/channels/$loginChannel").channel()["currentAmr"] as List<*>
                amr.shouldContainAll("kobil", "pin")
                amr shouldNotContain "password"
            }

            then("the PIN is in the release response and nowhere else - a later read does not repeat it") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)

                (releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String?).shouldNotBeNull()

                val reread = get("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil")
                reread.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "otp")
                reread.stepData().keys shouldNotContain "kobilPin"
            }

            then("a wrong unlock secret is an ordinary retryable failure and releases nothing") {
                val (_, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)

                val refused = releasePin(toolSessionId, """{"kind":"biometric","unlockSecret":"not-the-secret"}""")
                refused.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "unlock")
                refused.stepData().keys shouldNotContain "kobilPin"
            }

            then("redeeming before any release fails on that, not on the OTP") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)

                // The app cannot even get an OTP without the PIN, so this is a client that tried to
                // skip the release - answered as "unlock required", and the step stays there.
                val refused = redeem(toolSessionId, "12345678")
                refused.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "unlock")
                templateOf(refused.stepData()["error"]) shouldBe "Entsperren erforderlich"
            }

            then("a one-time password is spent once - replaying it does not authenticate again") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val firstTool = startAuth(loginChannel)
                val pin = releasePin(firstTool, biometric(device)).stepData()["kobilPin"] as String
                val otp = sdkLogin(device, pin)
                redeem(firstTool, otp).next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val replayChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val secondTool = startAuth(replayChannel)
                releasePin(secondTool, biometric(device))

                val replayed = redeem(secondTool, otp)
                replayed.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "otp")
                templateOf(replayed.stepData()["error"]) shouldBe "Bestaetigung nicht erkannt"
            }

            then("an assertion from a different device is refused without saying which one was expected") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String

                // The phone was replaced: KOBIL now knows a different device for this user, so the
                // assertion it files no longer matches the identifier the enrollment pinned.
                jdbcTemplate.update(
                    "UPDATE kobil_mock.ssms_user SET device_id = ? WHERE user_id = ?",
                    "dev-someone-elses",
                    device.kobilUserId,
                )

                val refused = redeem(toolSessionId, sdkLogin(device, pin))
                templateOf(refused.stepData()["error"]) shouldBe "Geraet nicht erkannt"
            }

            then("a device reporting a blocking risk is refused with its own reason, not folded into 'not recognized'") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                post(
                    "/mock-kobil/simulate-risk",
                    """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","risks":["ROOTED"]}"""
                )

                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String

                val refused = redeem(toolSessionId, sdkLogin(device, pin))
                templateOf(refused.stepData()["error"]) shouldBe "Geraet als unsicher gemeldet"
            }

            then("a non-blocking signal does not stand in the way") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                post(
                    "/mock-kobil/simulate-risk",
                    """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","risks":["OS_OUTDATED"]}"""
                )

                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String

                redeem(toolSessionId, sdkLogin(device, pin)).next() shouldBe
                    mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
            }
        }

    }

}
