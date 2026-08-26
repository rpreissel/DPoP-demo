package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.nimbusds.jose.jwk.JWK
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.HttpClientErrorException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.UUID

/**
 * Exercises the full REGISTRATION (ident-fsc -> enroll-sms -> authenticated), LOGIN (auth-sms),
 * Cancel/Logout and Back/Switch flows end to end against the real HTTP layer, matching
 * docs/05-api.md and docs/06-ablaeufe.md. DPoP validation is mocked (the crypto itself is
 * covered separately); everything downstream - orchestration, policy, persistence - is real.
 *
 * Channel resolution model (docs/02-domaenenmodell.md #3): the DPoP key only proves which
 * DEVICE is talking - `POST .../channels` always mints a brand-new ChannelSession, never
 * resumes one by key. "Same device, fresh app session" is simulated throughout by simply
 * calling `POST .../channels` again with the SAME mocked binding key and capturing the NEW
 * channelSessionId it returns; DeviceAccountLink is what still routes that new channel straight
 * to LOGIN for an already-registered device.
 *
 * The DB is wiped (mutable tables only; person/fsc_code seed data survives) before every test,
 * so every test can use the same test person without cross-test interference (IntegrationTestSupport).
 */
class RegistrationLoginStepUpFlowIntegrationTest : IntegrationTestSupport() {

    @MockitoBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach {
            val fakeJwk = mock(JWK::class.java)
            `when`(dpopValidator.validate(anyString(), anyString(), anyString())).thenAnswer {
                DpopProof(
                    token = "mock-token",
                    publicKey = fakeJwk,
                    jti = UUID.randomUUID().toString(),
                    htm = "POST",
                    htu = "http://localhost/mock",
                    issuedAt = Instant.now(),
                    nonce = null
                )
            }
            currentBindingKeyRef = "binding-" + UUID.randomUUID()
            `when`(jwkThumbprintService.computeThumbprint(fakeJwk)).thenAnswer { currentBindingKeyRef }
        }
    }

    /** Mock SMS is only printed to stdout (docs/05-api.md: TAN never appears in the response). */
    private fun captureMockTan(block: () -> Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val response = try {
            block()
        } finally {
            System.setOut(original)
        }
        val printed = buffer.toString()
        // Matches both "[MOCK SMS] TAN 123456 an ..." and "[MOCK EMAIL] Code 123456 an ...".
        val tan = Regex("""(?:TAN|Code) (\d{6}) an""").find(printed)?.groupValues?.get(1)
            ?: error("No mock TAN/code found in captured output: $printed")
        return tan to response
    }
    /** Runs ident-fsc through to Identified using the standard test person, returns the channelSessionId. */
    private fun identify(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        return channelSessionId
    }
    /**
     * Runs ident-fsc + enroll-sms + enroll-email through to AUTHENTICATED, returns the
     * channelSessionId. enroll-email is required even though sms alone already reaches the
     * default loa1 floor: a confirmed email is a Required Action of REGISTRATION
     * (docs/04-orchestrierung.md #2), not just an ACR-driven candidate.
     */
    private fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
        enrollEmail(channelSessionId)
        return channelSessionId
    }
    /** Runs enroll-email through to Completed on the given channel, returns the confirmed email. */
    private fun enrollEmail(channelSessionId: String): String {
        val email = "max.mustermann+${UUID.randomUUID()}@example.com"
        val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-email", """{"code":"$code"}""")
        return email
    }
    /** Registers via ident-fsc -> enroll-email -> enroll-password, returns the confirmed email. */
    private fun registerWithEmailAndPassword(password: String = "correct-horse-battery"): String {
        val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
        val channelSessionId = channelResponse.channel()["channelSessionId"] as String
        val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
        )
        val email = enrollEmail(channelSessionId)
        val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"$password"}""")
        return email
    }
    private fun linkedAccountsFor(bindingKeyRef: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM device_account_link WHERE binding_key_ref = ?", Int::class.java, bindingKeyRef
        ) ?: 0

    init {
        given("the registration, login, and step-up flow end to end") {
        then("Registration enrollment and subsequent login flow") {
            // 1) Channel init -> registration entry point (docs/05-api.md #2 example 1). Exactly one
            // ident method is registered, so the selection page is skipped straight to the tool
            // (same skip-if-single-candidate rule as ENROLL/AUTH, docs/04-orchestrierung.md #1).
            val channelResponse = post("/orchestrator/api/v1/app/channels")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            assertThat(channelResponse.channel()["state"]).isEqualTo("REGISTERING")
            assertThat(channelResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

            // 2) Activate ident-fsc
            val identActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc")
            val identToolSessionId = identActivation.nextRaw()["toolSessionId"] as String
            assertThat(identActivation.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
            @Suppress("UNCHECKED_CAST")
            assertThat(identActivation.stepData()["missingFields"] as List<String>).containsExactlyInAnyOrder("kvnr", "name", "vorname")

            // 3) Supply kvnr/name/vorname -> only fsc missing
            val afterNames = patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
            )
            @Suppress("UNCHECKED_CAST")
            assertThat(afterNames.stepData()["missingFields"] as List<String>).containsExactly("fsc")

            // 4) Supply the valid FSC -> identified; three enroll candidates exist (sms, email, device),
            // so the process offers a selection page instead of skipping straight to one of them.
            // enroll-password isn't offered yet - it requires a confirmed account email first.
            val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
            assertThat(identified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(identified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-email", "enroll-device")

            // 5) Activate enroll-sms
            val enrollActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms")
            val enrollToolSessionId = enrollActivation.nextRaw()["toolSessionId"] as String
            @Suppress("UNCHECKED_CAST")
            assertThat(enrollActivation.stepData()["missingFields"] as List<String>).containsExactly("phoneNumber")

            // 6) Supply phone number -> TAN sent (mock); demo mode also echoes it in the response's
            // `demo` object (never stepData - docs/05-api.md #2) so testers don't need server-log
            // access, and both must agree on the same TAN.
            val (enrollTan, afterPhone) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            assertThat(afterPhone.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput"))
            assertThat(afterPhone.stepData()).isEqualTo(mapOf("missingFields" to listOf("tan")))
            @Suppress("UNCHECKED_CAST")
            assertThat((afterPhone["demo"] as Map<String, Any?>)["tan"]).isEqualTo(enrollTan)
            // Mid-flow: tool responses never carry currentAcr/currentAmr/activeMethods
            // (docs/05-api.md #2) - not production data any tool step renders.
            assertThat(afterPhone.channel()).doesNotContainKeys("currentAcr", "currentAmr", "activeMethods")

            // 7) Confirm TAN -> enrolled, account now reaches loa2 with one factor. Not authenticated
            // yet though: a confirmed email is a Required Action of REGISTRATION
            // (docs/04-orchestrierung.md #2), independent of ACR - single remaining candidate, so the
            // client is skipped straight to enroll-email rather than a selection page.
            val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))
            assertThat(enrolled.channel()).doesNotContainKeys("currentAcr", "currentAmr", "activeMethods")

            // 8) Complete the required enroll-email step -> only now does registration finish.
            enrollEmail(channelSessionId)

            // 9) Channel now reports AUTHENTICATED with fsc+sms+email evidence
            val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
            assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
            @Suppress("UNCHECKED_CAST")
            assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "sms", "email")

            // --- Simulate a fresh app session on the SAME device (same DPoP key, no remembered
            // channelSessionId): POST always mints a brand-new channel, but DeviceAccountLink still
            // recognizes this device and routes it straight to LOGIN instead of ident-fsc. Two active
            // methods (sms, email) now exist, so a selection page is offered - pick auth-sms. ---
            val loginStart = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
            assertThat(newChannelSessionId).isNotEqualTo(channelSessionId)
            assertThat(loginStart.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")

            val (authTan, authActivation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String

            val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
        }
        then("Invalid phone number is rejected as bad request") {
            val channelSessionId = identify()
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"not-a-number"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        then("Create channel returns 201 with location pointing at the new channel") {
            val response = restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST, HttpEntity("{}", headers()), mapType
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            val channelSessionId = response.body!!.channel()["channelSessionId"] as String
            assertThat(response.headers.location.toString())
                .isEqualTo("http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId")
        }
        then("Tool activation returns 201 with location pointing at the tool resource") {
            val channelSessionId = identify()
            val response = restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms",
                HttpMethod.POST, HttpEntity("{}", headers()), mapType
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            val toolSessionId = response.body!!.nextRaw()["toolSessionId"] as String
            assertThat(response.headers.location.toString())
                .isEqualTo("http://localhost:$port/orchestrator/api/v1/tools/$toolSessionId/enroll-sms")
        }
        then("Resume mid enroll sms reuses the running tool session instead of sending a second tan") {
            // Get to right after phoneNumber was submitted (TAN already sent, awaiting tanInput) -
            // the exact point where an app restart used to reactivate enroll-sms and send a second TAN.
            val channelSessionId = identify()
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (enrollTan, afterPhone) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            assertThat(afterPhone.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput"))

            // Simulate "Sitzung fortsetzen" (resume, docs/05-api.md #2): the guaranteed resume entry
            // point is GET, never a reactivation. It must hand back the SAME toolSessionId that's
            // already awaiting the TAN, not a fresh one.
            val resumed = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(resumed.nextRaw()).isEqualTo(
                mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput", "toolSessionId" to enrollToolSessionId)
            )

            // The TAN captured before "resume" still confirms the SAME session - proving no second
            // TAN was needed and the phoneNumber already entered wasn't discarded. Registration isn't
            // finished yet though: confirmed email is still an outstanding Required Action.
            val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-email", "step" to "enroll"))
        }
        then("Exhausted retries end the process as gone") {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max"}"""
            )

            // First few wrong attempts stay retryable (200 + error in stepData, not an HTTP error).
            repeat(2) {
                val retryResponse = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
                assertThat(retryResponse.stepData()["error"]).isNotNull()
                assertThat(retryResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
            }

            // Retry limit (3) exhausted -> process aborted, 410 Gone.
            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"WRONGCODE"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
        }
        then("Binding key mismatch is forbidden") {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

            // A different DPoP key now claims to own this channelSessionId.
            currentBindingKeyRef = "a-completely-different-binding-key"

            val exception = assertThrows<HttpClientErrorException> {
                get("/orchestrator/api/v1/app/channels/$channelSessionId")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        }
        then("Step up to unreachable level aborts the process") {
            // Reuse the happy path up to AUTHENTICATED at loa2 with a single, already-used sms factor.
            val channelSessionId = registerAndAuthenticate()

            // loa3 requires two distinct factor types; this account only ever proves POSSESSION.
            val exception = assertThrows<HttpClientErrorException> {
                post("/orchestrator/api/v1/app/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.GONE)
        }
        then("Cancel during registration resets and offers a fresh start") {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            // Get all the way to Identified (account created) before cancelling, to prove the
            // channel doesn't stay half-bound to that account afterwards.
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
            // ChannelState diagram (docs/02-domaenenmodell.md #3): REGISTERING -> ANONYMOUS -> a
            // fresh registration is offered immediately, so the response already shows REGISTERING
            // again; single-candidate skip goes straight to the tool (same as the initial channel init).
            assertThat(cancelled.channel()["state"]).isEqualTo("REGISTERING")
            assertThat(cancelled.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

            // The old ident-fsc tool session is no longer part of any active process.
            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
        then("Cancel during login offers a fresh login attempt") {
            registerAndAuthenticate()

            // Simulate a fresh app session on the same device: new channel, straight to LOGIN via the device link.
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")

            val cancelled = delete("/orchestrator/api/v1/app/channels/$channelSessionId/process")
            // LOGIN cancel doesn't force a channel-state change (docs: only REGISTERING/STEP_UP do);
            // the response re-offers candidates from scratch - two active methods (sms, email) now
            // exist, so that's a selection page, not the single auth-sms tool directly.
            assertThat(cancelled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(cancelled.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")
        }
        then("Reidentifying an already provisioned account offers auth instead of enrollment") {
            // First full registration: creates an account for KVNR A123456789 with an active sms method.
            registerAndAuthenticate()

            // A brand-new channel (e.g. a different device) identifies with the SAME KVNR -
            // findOrCreateAccount (docs/05-api.md #2) reuses the existing account instead of a second one.
            currentBindingKeyRef = "binding-" + UUID.randomUUID()
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            val identified = patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            // The reused account already has active sms/email methods reaching loa2 - nothing left to
            // enroll. Offer proving one of those existing methods instead of dead-ending (previously:
            // 410 PROCESS_ABORTED, since enrollmentCandidates came back empty -
            // docs/04-orchestrierung.md #1). Two candidates -> a selection page, not a direct skip.
            assertThat(identified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(identified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")
        }
        then("Duplicate activation orphans the previous tool session cleanly") {
            registerAndAuthenticate()

            // Simulate a fresh app session and activate auth-sms TWICE (e.g. a double client
            // request) - each activation mints its own ToolSession with its own issued TAN.
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

            val firstActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
            val firstToolSessionId = firstActivation.nextRaw()["toolSessionId"] as String
            val secondActivation = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
            val secondToolSessionId = secondActivation.nextRaw()["toolSessionId"] as String
            assertThat(secondToolSessionId).isNotEqualTo(firstToolSessionId)

            // The first (now superseded) ToolSession is cleanly rejected - not the confusing
            // module-internal "Unknown tool session" error that surfaced before this fix.
            @Suppress("UNCHECKED_CAST")
            val firstTan = (firstActivation["demo"] as Map<String, Any?>)["tan"] as String
            val rejected = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"$firstTan"}""")
            }
            assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)

            // The second (current) ToolSession works normally with its own TAN.
            @Suppress("UNCHECKED_CAST")
            val secondTan = (secondActivation["demo"] as Map<String, Any?>)["tan"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"$secondTan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))
        }
        then("Switch away from ident tool cancels the whole process") {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

            // No prior step to go "back" to for the first (IDENT) tool - equivalent to Cancel.
            // Single-candidate skip goes straight back to the tool (same as the initial channel init).
            val result = delete("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc")
            assertThat(result.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

            val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(channel.channel()["state"]).isEqualTo("REGISTERING")
        }
        then("Switch away from enroll tool reoffers enrollment candidates") {
            val channelSessionId = identify()
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

            // Three enrollment methods are actually offerable at this point (enroll-password needs a
            // confirmed email first), so switching away re-offers the selection page - but the OLD
            // tool session is abandoned either way.
            val result = delete("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms")
            assertThat(result.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(result.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-email", "enroll-device")

            // The abandoned tool session is gone even though we re-activate the same toolId.
            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

            // Re-activating works fine and mints a new tool session.
            val reactivated = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms")
            assertThat(reactivated.nextRaw()["toolSessionId"]).isNotEqualTo(enrollToolSessionId)
        }
        then("Password enrollment and subsequent login flow") {
            // 1) Identify, confirm email (password's precondition), then enroll password. Channel
            // requires loa2 up front so registration doesn't auto-finish after email alone (which,
            // like sms, only reaches loa1 by itself) before password is ever offered.
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            enrollEmail(channelSessionId)
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String

            // 2) Password alone in one call - the credential is self-verifying, no confirmation handshake.
            val enrolled = patch(
                "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password",
                """{"password":"correct-horse-battery"}"""
            )
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(finalChannel.channel()["state"]).isEqualTo("AUTHENTICATED")
            assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
            @Suppress("UNCHECKED_CAST")
            assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "email", "password")

            // --- Simulate a fresh app session on the same device ---
            val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
            // Neither email nor password alone reaches loa2 - the login offers a pick between both.
            assertThat(loginStart.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-email", "auth-password")

            val authActivation = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-password")
            val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String

            // 3) Wrong password first - retryable, not an HTTP error.
            val retry = patch(
                "/orchestrator/api/v1/tools/$authToolSessionId/auth-password",
                """{"password":"wrong-password"}"""
            )
            assertThat(retry.stepData()["error"]).isNotNull()
            assertThat(retry.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth"))

            // 4) Correct password -> completes password, but loa2 needs the second (differently-typed) factor too.
            val afterPassword = patch(
                "/orchestrator/api/v1/tools/$authToolSessionId/auth-password",
                """{"password":"correct-horse-battery"}"""
            )
            assertThat(afterPassword.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-email", "step" to "auth"))

            val (code, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-email")
            }
            val authEmailToolSessionId = activation.nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-email", """{"code":"$code"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
        }
        then("Mfa combination sms and password together reach loa 2 while each alone is only loa 1") {
            // Channel requires loa2 up front, so registration can't stop after a single loa1-rated
            // factor - it must chain further, differently-typed ones too.
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            // First factor (sms): alone it's loa1, not the required loa2, so registration continues.
            val enrollSmsToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (smsTan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
            // Two candidates are left (enroll-email, enroll-device; sms is already active, password
            // still needs a confirmed email first) - a selection page is offered, not a single-
            // candidate skip.
            assertThat(afterSms.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(afterSms.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-email", "enroll-device")

            // Second factor (email): sms+email are BOTH possession, so this alone still doesn't
            // reach loa2 - but it unlocks enroll-password (requiresConfirmedEmail).
            enrollEmail(channelSessionId)

            // Third factor (password, a KNOWLEDGE factor): together with sms/email this combines to loa2.
            val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
            val enrolled = patch(
                "/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password",
                """{"password":"correct-horse-battery"}"""
            )
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val finalChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(finalChannel.channel()["currentAcr"]).isEqualTo("loa2")
            @Suppress("UNCHECKED_CAST")
            assertThat(finalChannel.channel()["currentAmr"] as List<String>).containsExactlyInAnyOrder("fsc", "sms", "email", "password")

            // --- Fresh app session on the same device (no re-identification, so fsc's own loa2 isn't in play this time) ---
            val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
            // No single method alone reaches loa2, so the login offers a pick among all three.
            assertThat(loginStart.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email", "auth-password")

            val (loginTan, smsActivation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authSmsToolSessionId = smsActivation.nextRaw()["toolSessionId"] as String
            val afterSmsAuth = patch("/orchestrator/api/v1/tools/$authSmsToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
            // sms alone is only loa1, and email would be the SAME factor type (no MFA progress) - only
            // password is offered next.
            assertThat(afterSmsAuth.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth"))

            // Same rule as above: activate auth-password explicitly, don't reuse afterSmsAuth's (auth-sms) session id.
            val authPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
            val authenticated = patch(
                "/orchestrator/api/v1/tools/$authPasswordToolSessionId/auth-password",
                """{"password":"correct-horse-battery"}"""
            )
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa2")
        }
        then("Device link is written assoon as one auth method exists not only once the channels own floor is reached") {
            // Channel requires loa2, so a single loa1-rated method isn't enough for THIS channel -
            // registration doesn't finish yet, it offers a selection page next (enroll-email/-device).
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
            assertThat(afterSms.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))

            // Abandon here (never enroll email/password/device, never reach this channel's own loa2
            // floor) -
            // a fresh app session (new channel, plain default loa1 floor) must still recognize this
            // device via the sms method already on file, not fall back to ident-fsc.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth"))

            val (loginTan, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/${newChannel.channel()["channelSessionId"]}/tools/auth-sms")
            }
            val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$loginTan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))
        }
        then("Device link is also written when re identifying into an already enrolled account not only on first enrollment") {
            // Register+enroll fully on binding key #1 (writes the DeviceAccountLink for key #1).
            registerAndAuthenticate()

            // Simulate a device whose key isn't linked yet (e.g. a fresh browser profile, or the
            // original key was lost) - "auto" correctly falls back to ident-fsc since key #2 has no
            // link yet.
            currentBindingKeyRef = "binding-" + UUID.randomUUID()
            val reidentified = post("/orchestrator/api/v1/app/channels")
            assertThat(reidentified.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
            val channelSessionId = reidentified.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            // The account already has sms enrolled from before, so this offers ordinary auth-sms
            // (not enrollment) - proving it is a ToolOutcome.Completed.Authenticated with
            // outcome.accountId == null (account was already known from the channel, not resolved
            // via lookup). Without also linking here, this device's key #2 would never get a
            // DeviceAccountLink and would be forced back through ident-fsc on every future connect.
            val (tan, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms")
            }
            val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")

            // A third, brand-new channel on the SAME key #2 must now skip straight to LOGIN too -
            // two active methods (sms, email) means a selection page, not a direct skip.
            val thirdChannel = post("/orchestrator/api/v1/app/channels")
            assertThat(thirdChannel.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(thirdChannel.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")
        }
        then("Logout ends the channel for good a new one starts a fresh login via the device link") {
            val channelSessionId = registerAndAuthenticate()
            val beforeLogout = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(beforeLogout.channel()["state"]).isEqualTo("AUTHENTICATED")

            // Unlike Cancel (which leaves an AUTHENTICATED channel untouched, nothing to cancel),
            // Logout always ends the channel.
            assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

            // The old channelSessionId is dead - GET still resolves it (same key, valid binding),
            // but it stays LOGGED_OUT and reports no next step; it is never silently re-derived.
            val loggedOutChannel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(loggedOutChannel.channel()["state"]).isEqualTo("LOGGED_OUT")
            assertThat(loggedOutChannel["next"]).isNull()
            assertThat(loggedOutChannel.channel()["currentAcr"]).isNull()

            // A brand-new channel on the SAME device (same DPoP key) still recognizes the account via
            // DeviceAccountLink and skips straight to LOGIN instead of a fresh ident-fsc - two active
            // methods (sms, email) means a selection page, not a direct skip.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
            assertThat(newChannelSessionId).isNotEqualTo(channelSessionId)
            assertThat(newChannel.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(newChannel.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms", "auth-email")

            val (tan, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["state"]).isEqualTo("AUTHENTICATED")
        }
        then("Logout during active registration cancels the process too") {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )

            assertThat(deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")).isEqualTo(HttpStatus.NO_CONTENT)

            // The old ident-fsc tool session is no longer part of any active process.
            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)

            // Half-registered isn't a returning user (same rule as plain Cancel, docs/06-ablaeufe.md
            // via ProcessCancellationService): no account was ever fully provisioned, so the new
            // channel starts registration again, not LOGIN.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            assertThat(newChannel.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        }
        then("Logout with mismatched binding key is forbidden") {
            val channelSessionId = registerAndAuthenticate()

            currentBindingKeyRef = "a-completely-different-binding-key"

            val exception = assertThrows<HttpClientErrorException> {
                deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        }
        then("Weak password is rejected as bad request") {
            // loa2 up front: default loa1 would already be satisfied by email alone, ending
            // registration (finishAsAuthenticated -> process consumed) before enroll-password could
            // ever be activated.
            val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            enrollEmail(channelSessionId)
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String

            val exception = assertThrows<HttpClientErrorException> {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"short"}""")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        then("Enroll password without confirmed email is rejected as conflict") {
            // Bypass attempt: activate enroll-password directly while still in an "enrollment"
            // selection context, without ever having confirmed an email - validateActivation alone
            // only checks the CATEGORY (ENROLL) matches, not that this specific toolId was actually
            // offered, so ToolControllerSupport must enforce the requiresConfirmedEmail precondition
            // itself, not just rely on it being excluded from stepData.options.
            val channelSessionId = identify()

            val exception = assertThrows<HttpClientErrorException> {
                post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
        then("Account level throttle locks after repeated failed auth attempts across fresh tool sessions") {
            registerAndAuthenticate()
            // Each iteration is a FRESH channel and therefore a fresh journey with a fresh attempt
            // budget - which is precisely what the journey-local budget cannot catch and the
            // account-level throttle must (docs/04-orchestrierung.md #7).
            repeat(5) {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"000000"}""")
            }

            val lockedChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val exception = assertThrows<HttpClientErrorException> {
                post("/orchestrator/api/v1/app/channels/$lockedChannelSessionId/tools/auth-sms")
            }
            assertThat(exception.statusCode.value()).isEqualTo(423)
        }
        then("Account level throttle resets on successful auth") {
            registerAndAuthenticate()
            // A few failures, but not enough to lock - then a genuine success should clear the counter.
            // Each failure gets its own channel: the journey-local attempt budget would otherwise end
            // the journey before the account-level counter is anywhere near its own threshold.
            repeat(3) {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"000000"}""")
            }
            val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val (tan, activation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$freshChannelSessionId/tools/auth-sms")
            }
            val toolSessionId = activation.nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms", """{"tan":"$tan"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            // Confirm the counter was actually reset, not just "not yet locked": two more fresh
            // failures on ANOTHER new session right after a success should NOT be treated as
            // already at 3/5 - they still land on the same account via the device link.
            repeat(2) {
                val retryChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val retryToolSessionId = post("/orchestrator/api/v1/app/channels/$retryChannelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$retryToolSessionId/auth-sms", """{"tan":"000000"}""")
            }
            // Still allowed - only 2 failures since the reset, well under the lock threshold.
            val nextChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val stillAllowed = post("/orchestrator/api/v1/app/channels/$nextChannelSessionId/tools/auth-sms")
            assertThat(stillAllowed.nextRaw()["toolSessionId"]).isNotNull()
        }
        then("Get methods reads the same active methods as the channel response") {
            // No account known yet - empty collection, not an error (docs/05-api.md #2).
            val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            @Suppress("UNCHECKED_CAST")
            assertThat((get("/orchestrator/api/v1/app/channels/$freshChannelSessionId/methods")["methods"] as List<*>)).isEmpty()

            val channelSessionId = registerAndAuthenticate()
            @Suppress("UNCHECKED_CAST")
            val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
            assertThat(methods.methodNames()).containsExactlyInAnyOrder("sms", "email")

            val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            @Suppress("UNCHECKED_CAST")
            assertThat(channel.channel()["activeMethods"] as List<Map<String, Any?>>).isEqualTo(methods)
        }
        then("Manage methods adds another method on an authenticated channel") {
            val channelSessionId = registerAndAuthenticate()

            val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
            // sms and email already active (email via the REGISTRATION Required Action); password and
            // device are offered - two candidates means a selection page, not a single-candidate skip.
            assertThat(started.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(started.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-password", "enroll-device")

            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
            val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")
            // Finishes immediately after ONE enrollment, regardless of whether some higher floor was
            // reached - unlike the identification path, MANAGE never depends on canAccountReach.
            assertThat(enrolled.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
            @Suppress("UNCHECKED_CAST")
            assertThat(channel.channel()["currentAmr"] as List<String>).contains("password")
        }
        then("Manage methods offers the last remaining candidate after sms email and password are active") {
            // sms + email already active from registerAndAuthenticate (email via the REGISTRATION
            // Required Action) - only password is missing to match this test's name.
            val channelSessionId = registerAndAuthenticate()
            post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
            val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

            // sms, email and password are now active - enroll-device is the one remaining catalog
            // candidate (single-candidate skip goes straight to it; the "nothing left" message is
            // covered once device is also enrolled, see DeviceBindingIntegrationTest).
            val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
            assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "enroll-device", "step" to "enroll"))
        }
        then("Deactivate method rejects when it would drop below the channels floor") {
            // sms+email alone (both POSSESSION, registerAndAuthenticate's default) can't demonstrate
            // this anymore: email alone already covers the default loa1 floor, so deactivating sms
            // wouldn't drop below it. Use an explicit loa2 channel with sms+email+password instead
            // (same recipe as mfaCombination_smsAndPasswordTogetherReachLoa2Test) - sms/email are both
            // POSSESSION, password is the only KNOWLEDGE factor, so deactivating IT is what breaks the
            // MFA combination the loa2 floor requires.
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            val enrollSmsToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (smsTan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
            enrollEmail(channelSessionId)
            val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

            @Suppress("UNCHECKED_CAST")
            val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
            val passwordInstanceId = methods.first { it["method"] == "password" }["id"] as String

            val exception = assertThrows<HttpClientErrorException> {
                delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/$passwordInstanceId")
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
        then("Deactivate method succeeds when another active method still covers the floor") {
            // sms+email already active from registerAndAuthenticate (email via the REGISTRATION
            // Required Action) - email alone covers the default loa1 floor, so deactivating sms is safe.
            val channelSessionId = registerAndAuthenticate()
            @Suppress("UNCHECKED_CAST")
            val methods = get("/orchestrator/api/v1/app/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
            val smsInstanceId = methods.first { it["method"] == "sms" }["id"] as String

            delete("/orchestrator/api/v1/app/channels/$channelSessionId/methods/$smsInstanceId")

            // sms is a candidate again now that it was deactivated - email is already confirmed, so
            // password is ALSO now a valid candidate, hence a selection page rather than a skip.
            val started = post("/orchestrator/api/v1/app/channels/$channelSessionId/enrollments")
            assertThat(started.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(started.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-sms", "enroll-password", "enroll-device")
        }
        then("Manage methods steps up to loa 2 when session only has loa 1 evidence") {
            // Register with fsc+sms in one continuous session (loa2), then simulate a completely
            // fresh app session on the same device: DeviceAccountLink skips straight to LOGIN via
            // auth-sms alone, never re-proving fsc, so this session's own evidence sits at loa1.
            registerAndAuthenticate()
            val loginStart = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
            val (authTan, authActivation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")
            val afterLogin = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterLogin.channel()["currentAcr"]).isEqualTo("loa1")

            // The account has only sms enrolled - no second AUTH method exists to combine with, so
            // without re-identification this would be a dead end (the bug this test guards against).
            // MANAGE must offer ident-fsc as a way to reach loa2 instead of erroring out.
            val started = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
            assertThat(started.channel()["state"]).isEqualTo("STEP_UP_IN_PROGRESS")
            assertThat(started.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))

            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            val reIdentified = patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            // Re-identification alone already reaches loa2, so the step-up sub-journey ends - and the
            // ORIGINAL wish resumes right there. The user does not have to ask for the enrollment a
            // second time; that is the whole point of parking the wish rather than replacing it.
            assertThat(reIdentified.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(reIdentified.stepData()["options"] as List<String>).containsExactlyInAnyOrder("enroll-password", "enroll-device")

            val afterStepUp = get("/orchestrator/api/v1/app/channels/$newChannelSessionId")
            assertThat(afterStepUp.channel()["currentAcr"]).isEqualTo("loa2")
        }
        then("Manage methods step up re identifying as a different person is rejected") {
            registerAndAuthenticate()
            val loginStart = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
            val (authTan, authActivation) = captureMockTan {
                post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/auth-sms")
            }
            val authToolSessionId = authActivation.nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$authTan"}""")

            post("/orchestrator/api/v1/app/channels/$newChannelSessionId/enrollments")
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$newChannelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

            // A different KVNR resolves to a different person/account - must not silently take over
            // this session's account.
            val exception = assertThrows<HttpClientErrorException> {
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"B987654321","name":"Beispiel","vorname":"Erika","fsc":"ERIKA123"}"""
                )
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
        then("Lookup login via password authenticates into existing account and relinks the device") {
            val email = registerWithEmailAndPassword()

            // Same mocked device, but intent=login forces lookup-based login regardless of the
            // DeviceAccountLink this device already has from registerWithEmailAndPassword above
            // (docs/04-orchestrierung.md, lookup-based login).
            val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            val channelSessionId = loginStart.channel()["channelSessionId"] as String
            assertThat(loginStart.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod"))
            @Suppress("UNCHECKED_CAST")
            assertThat(loginStart.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")

            val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
            val authenticated = patch(
                "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                """{"email":"$email","password":"correct-horse-battery"}"""
            )
            assertThat(authenticated.next()).isEqualTo(
                mapOf("type" to "orchestrator", "context" to "authentication", "step" to "offerDeviceBinding")
            )

            post("/orchestrator/api/v1/app/channels/$channelSessionId/device-binding", """{"accept":true}""")

            val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
            assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
            @Suppress("UNCHECKED_CAST")
            assertThat(channel.channel()["currentAmr"] as List<String>).contains("password")

            // Accepting is what writes DeviceAccountLink - a subsequent FAST channel on this device
            // is then recognized instead of having to identify again.
            val nextAuto = post("/orchestrator/api/v1/app/channels")
            assertThat(nextAuto.channel()["state"]).isNotEqualTo("REGISTERING")
        }
        then("Lookup login via sms authenticates into existing account") {
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            val enrollToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (enrollTan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
            val email = enrollEmail(channelSessionId)

            val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
            val lookupToolSessionId = post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

            val (loginTan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"email":"$email"}""")
            }
            val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"tan":"$loginTan"}""")
            // A lookup login does not finish on the proof itself: the device binding is offered
            // explicitly, because this intent is chosen by people who want no device binding.
            assertThat(authenticated.next()).isEqualTo(
                mapOf("type" to "orchestrator", "context" to "authentication", "step" to "offerDeviceBinding")
            )

            val done = post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/device-binding", """{"accept":true}""")
            assertThat(done.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val channel = get("/orchestrator/api/v1/app/channels/$lookupChannelSessionId")
            assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
        }
        then("Lookup login declining the binding offer leaves the device unlinked") {
            val email = registerWithEmailAndPassword()

            // A DIFFERENT physical device: it has never been linked, so whether a link exists
            // afterwards is decided purely by the answer to the binding offer.
            currentBindingKeyRef = "binding-" + UUID.randomUUID()

            val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            val channelSessionId = loginStart.channel()["channelSessionId"] as String
            val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                """{"email":"$email","password":"correct-horse-battery"}"""
            )

            post("/orchestrator/api/v1/app/channels/$channelSessionId/device-binding", """{"accept":false}""")
            assertThat(linkedAccountsFor(currentBindingKeyRef)).isZero()

            // And a plain FAST channel on this device consequently still has to identify.
            val nextAuto = post("/orchestrator/api/v1/app/channels")
            assertThat(nextAuto.channel()["state"]).isEqualTo("REGISTERING")
        }
        /**
         * activeMethods (ChannelResponse) is the account's full standing method list, distinct from
         * currentAmr (session evidence, docs/10-frontend.md). A device-bound LOGIN that only needs
         * ONE active method to satisfy the default loa1 floor never re-proves the account's other
         * methods - activeMethods must still report them, so the UI can offer to manage (e.g.
         * deactivate) a method the current session never touched.
         */
        then("Channel response active methods includes methods not proven this session") {
            // loa2 up front: default loa1 would already be satisfied by email alone, ending
            // registration (finishAsAuthenticated -> process consumed) before enroll-password could
            // ever be activated (same reasoning as passwordEnrollmentAndSubsequentLoginFlow above).
            val channelSessionId =
                post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"VALIDCODE"}"""
            )
            enrollEmail(channelSessionId)
            val enrollPasswordToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
            patch("/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password", """{"password":"correct-horse-battery"}""")

            deleteNoContent("/orchestrator/api/v1/app/channels/$channelSessionId")

            // Fresh device-bound LOGIN (same device, DeviceAccountLink still points here): default
            // loa1 floor is satisfied by a single method, so this proves ONLY password.
            val loginStart = post("/orchestrator/api/v1/app/channels")
            val loginChannelSessionId = loginStart.channel()["channelSessionId"] as String
            val authToolSessionId = post("/orchestrator/api/v1/app/channels/$loginChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
            val authenticated = patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
            assertThat(authenticated.next()).isEqualTo(mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated"))

            val channel = get("/orchestrator/api/v1/app/channels/$loginChannelSessionId")
            @Suppress("UNCHECKED_CAST")
            assertThat(channel.channel()["currentAmr"] as List<String>).containsExactly("password")
            @Suppress("UNCHECKED_CAST")
            assertThat((channel.channel()["activeMethods"] as List<*>).methodNames()).containsExactlyInAnyOrder("email", "password")
        }
        then("Lookup login via email authenticates into existing account") {
            val email = registerWithEmailAndPassword()

            val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
            val lookupToolSessionId = post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String

            val (loginCode, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"email":"$email"}""")
            }
            val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"code":"$loginCode"}""")
            assertThat(authenticated.next()).isEqualTo(
                mapOf("type" to "orchestrator", "context" to "authentication", "step" to "offerDeviceBinding")
            )
            post("/orchestrator/api/v1/app/channels/$lookupChannelSessionId/device-binding", """{"accept":true}""")

            val channel = get("/orchestrator/api/v1/app/channels/$lookupChannelSessionId")
            assertThat(channel.channel()["state"]).isEqualTo("AUTHENTICATED")
            @Suppress("UNCHECKED_CAST")
            assertThat(channel.channel()["currentAmr"] as List<String>).contains("email")
        }
        then("Lookup login with unknown email fails indistinguishably from a wrong credential") {
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            val channelSessionId = channelResponse.channel()["channelSessionId"] as String
            val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String

            // Same shape as a wrong password against a real account (200, Failed -> retry offered)
            // - never a distinct HTTP error for "unknown email" (enumeration protection).
            val response = patch(
                "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                """{"email":"nobody@example.com","password":"whatever12"}"""
            )
            assertThat(response.stepData()["error"]).isNotNull()
            assertThat(response.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "auth-password-lookup", "step" to "auth"))
        }
        /**
         * Covers auth-email-lookup specifically: its account resolution lives in the handler (the one
         * module allowed to read `account`), unlike auth-password-lookup's, which the controller still
         * feeds in. Both must stay indistinguishable to a caller probing for existing addresses.
         */
        then("Lookup login via email with unknown email is indistinguishable from a known one") {
            val knownEmail = registerWithEmailAndPassword()

            fun submit(email: String): Map<String, Any?> {
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""").channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String
                return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"email":"$email"}""").next()
            }

            // Identical `next` for both - a different step, toolId or error would reveal whether the
            // address exists. Only the (invisible) mail send and the stored accountId differ.
            assertThat(submit("nobody@example.com"))
                .isEqualTo(submit(knownEmail))
                .isEqualTo(mapOf("type" to "tool", "toolId" to "auth-email-lookup", "step" to "codeInput"))
        }
        then("Lookup login intent on never linked device offers lookup tools not registration") {
            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"login"}""")
            @Suppress("UNCHECKED_CAST")
            assertThat(channelResponse.stepData()["options"] as List<String>).containsExactlyInAnyOrder("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup")
        }
        then("Register intent on already linked device starts fresh registration instead") {
            registerAndAuthenticate()

            val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")
            assertThat(channelResponse.channel()["state"]).isEqualTo("REGISTERING")
            assertThat(channelResponse.next()).isEqualTo(mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input"))
        }
        /**
         * DpopValidator is mocked everywhere else in this suite, so this is the one path that
         * genuinely exercises DpopBindingKeyResolver rather than the mock: a missing header is caught
         * before the mocked validator is ever called (docs/04-orchestrierung.md #5, DPoP-demo-2tm.3).
         * Same 401 the deleted DpopBaseController produced.
         */
        then("Missing dpop header is rejected as unauthorized before any controller logic runs") {
            val headersWithoutDpop = HttpHeaders().apply { set("Content-Type", "application/json") }
            val exception = assertThrows<HttpClientErrorException> {
                restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                    HttpEntity("{}", headersWithoutDpop), mapType
                )
            }
            assertThat(exception.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        }
        }
    }
}
