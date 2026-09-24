package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.kernel.ChannelType
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.tool.ToolAvailabilityService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Tool availability (docs/03-tool-architektur.md): a client declares which toolIds it supports at
 * channel creation, and the backend can additionally kill-switch a tool at runtime - both axes
 * narrow [com.example.dpop.orchestrator.journey.state.JourneyState.activatable] live, on every
 * request, not just at the moment a candidate list was first computed.
 */
class ToolAvailabilityIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var toolAvailabilityService: ToolAvailabilityService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun adminAvailability(): List<Map<String, Any?>> = restTemplate.exchange(
        "http://localhost:$port/orchestrator/admin/tools/availability", org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<Void>(adminHeaders()),
        object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {}
    ).body!!

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.tools(): List<Map<String, Any?>> = this["tools"] as List<Map<String, Any?>>

    /** Every toolId a response currently points at or lists as an option, whichever applies. */
    @Suppress("UNCHECKED_CAST")
    private fun offeredToolIds(response: Map<String, Any?>): List<String> {
        val stepData = response["stepData"] as? Map<String, Any?>
        val options = (stepData?.get("options") as? List<String>).orEmpty()
        return options + listOfNotNull(response.nextRaw()["toolId"] as? String)
    }

    init {
        given("an account with two active auth methods (sms + email) and a backend disable in effect") {
            `when`("resuming the channel after the backend disables one of them mid-journey") {
                then("the disabled one silently disappears from the offer without any client action") {

                seedRegisteredAccount()
                // Same device, still linked: a fresh channel offers both as an AuthChoice. Options
                // are only handed out once, at the transition that produced this state (the create
                // response itself) - a later GET/resume never re-sends stepData.options, only `next`.
                val created = post("/orchestrator/api/v1/app/channels")
                val channelSessionId = created.channel()["channelSessionId"] as String
                created.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                created.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

                // The backend disables auth-sms WHILE the client is sitting on that very offer -
                // nobody declined anything, the state in the DB is untouched.
                toolAvailabilityService.disable("auth-sms", ChannelType.APP, "suspected compromise")

                // A plain GET (no journey transition) already reflects it: live filtering in
                // activatable(), not a snapshot frozen at the last state transition.
                val afterDisable = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterDisable.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")

                // Direct activation of the disabled tool is rejected too, not just omitted from the offer.
                val exception = assertThrows<HttpClientErrorException> {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                // The remaining candidate still works normally.
                val activation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password")
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                }
            }
        }

        given("an account whose only active auth methods are both backend-disabled") {
            `when`("a fresh entry journey computes its first offer") {
                then("the existing fallback to identification is reused, not a new dead end") {

                seedRegisteredAccount()
                toolAvailabilityService.disable("auth-sms", ChannelType.APP, "maintenance")
                toolAvailabilityService.disable("auth-password", ChannelType.APP, "maintenance")

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val next = get("/orchestrator/api/v1/channels/$channelSessionId").next()
                next shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")

                }
            }
        }

        given("a client that declares only a subset of the catalog as available") {
            `when`("registering with availableTools restricted to ident-fsc, enroll-sms and enroll-email") {
                then("enroll-password/enroll-device are never offered, and the restricted path still completes") {

                // A REGISTRATION journey has mandatory steps of its own (a confirmed email is a
                // Required Action, docs/04-orchestrierung.md #2) - restricting to a set that can
                // still fulfill them (unlike ident-fsc alone, which would abort with 410) proves
                // availability narrows candidate OFFERS without breaking the journey itself.
                val channelSessionId = post(
                    "/orchestrator/api/v1/app/channels",
                    """{"availableTools":["ident-fsc","enroll-sms","confirm-email"]}"""
                ).channel()["channelSessionId"] as String

                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val identified = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","fsc":"VALIDCODE"}"""
                )
                // The address is the first obligation now, before any enrollment is offered - it is
                // in this channel's availableTools, so the step really runs here.
                identified.nextRaw()["toolId"] shouldBe "confirm-email"
                confirmEmail(channelSessionId)
                val afterConfirm = get("/orchestrator/api/v1/channels/$channelSessionId")
                offeredToolIds(afterConfirm) shouldNotContain "enroll-password"
                offeredToolIds(afterConfirm) shouldNotContain "enroll-device"

                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$tan"}""")
                offeredToolIds(afterSms) shouldNotContain "enroll-password"
                offeredToolIds(afterSms) shouldNotContain "enroll-device"

                val final = get("/orchestrator/api/v1/channels/$channelSessionId")
                final.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                }
            }
        }

        given("the public catalog and the admin availability endpoints") {
            `when`("listing the catalog, then toggling one tool off for the App channel only") {
                then("the admin view reflects it for App and leaves Web untouched") {

                // GET /tools/catalog returns a JSON array, not the {channel,next,...} envelope, so
                // the shared get() helper (which assumes an object body) doesn't fit here.
                val catalogEntries = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/tools/catalog", org.springframework.http.HttpMethod.GET,
                    org.springframework.http.HttpEntity<Void>(headers()),
                    object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {}
                ).body!!
                catalogEntries.map { it["toolId"] } shouldContain "auth-sms"

                fun enabledIn(channel: String): Boolean? = adminAvailability()
                    .first { it["channel"] == channel }.tools().first { it["toolId"] == "auth-sms" }["enabled"] as Boolean?

                enabledIn("APP") shouldBe true
                put("/orchestrator/admin/tools/auth-sms/availability/APP", """{"enabled":false,"reason":"test"}""") shouldBe HttpStatus.OK
                enabledIn("APP") shouldBe false
                enabledIn("KEYCLOAK") shouldBe true

                }
            }
        }

        given("an account with two auth methods, sms and password") {
            `when`("the operator sets the App channel's order, then reverses it") {
                then("the selection lists the options in exactly that order, live") {

                seedRegisteredAccount()
                put("/orchestrator/admin/tools/order/APP", """{"toolIds":["auth-password","auth-sms"]}""") shouldBe HttpStatus.OK
                val created = post("/orchestrator/api/v1/app/channels")
                @Suppress("UNCHECKED_CAST")
                created.stepData()["options"] as List<String> shouldBe listOf("auth-password", "auth-sms")

                put("/orchestrator/admin/tools/order/APP", """{"toolIds":["auth-sms","auth-password"]}""") shouldBe HttpStatus.OK
                // Same journey, next screen: declining nothing, just re-reading after switching
                // the order is not a new transition, so re-create to get a fresh offer rendered.
                @Suppress("UNCHECKED_CAST")
                post("/orchestrator/api/v1/app/channels").stepData()["options"] as List<String> shouldBe listOf("auth-sms", "auth-password")

                }
            }
        }

        given("an auth method switched off for the Web channel only") {
            `when`("an App channel computes its offer") {
                then("the App channel still offers it") {

                seedRegisteredAccount()
                toolAvailabilityService.disable("auth-sms", ChannelType.KEYCLOAK, "web only")

                @Suppress("UNCHECKED_CAST")
                post("/orchestrator/api/v1/app/channels").stepData()["options"] as List<String> shouldContain "auth-sms"

                }
            }
        }

        given("a channel creation request without availableTools") {
            `when`("posting the raw request") {
                then("it is rejected as a bad request") {

                val exception = assertThrows<HttpClientErrorException> {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/app/channels", org.springframework.http.HttpMethod.POST,
                        org.springframework.http.HttpEntity("{}", headers()), mapType
                    )
                }
                exception.statusCode shouldBe HttpStatus.BAD_REQUEST

                }
            }
        }
    }
}
