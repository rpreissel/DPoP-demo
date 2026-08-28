package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Backing out of the currently activated tool (Back/Switch)
 *
 * Split out of what used to be one 1300-line RegistrationLoginStepUpFlowIntegrationTest -
 * see IntegrationTestSupport for the shared HTTP-client/DB-reset/flow-helper plumbing every
 * orchestrator integration suite builds on.
 */
class SwitchBackIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a fresh channel") {
            `when`("switching away from the ident-fsc tool") {
                then("the fallback chain moves to the only remaining identification candidate") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/app/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                // Identification is a FALLBACK state (declining moves on, same rule as
                // PreferredAuth/AuthChoice): ident-fsc is now declined, leaving ident-eid as the sole
                // remaining candidate - single-candidate skip activates it directly rather than
                // showing a trivial one-option selection page.
                val result = delete("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc")
                result.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-eid", "step" to "input")

                val channel = get("/orchestrator/api/v1/app/channels/$channelSessionId")
                assertThat(channel.channel()["state"]).isEqualTo("REGISTERING")


                }
            }
        }

        given("an identified channel") {
            `when`("switching away from an enroll tool") {
                then("enrollment candidates are re-offered") {

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
            }
        }
    }
}
