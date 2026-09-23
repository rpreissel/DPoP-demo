package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod

/**
 * The rich, per-step journey trace (docs/04-orchestrierung.md), distinct from the minimized
 * orchestrator.session_event audit trail - see JourneyLogEntry's own doc. Read the only way it
 * can be read now: the operator's view across all accounts (`GET /orchestrator/admin/journey-log`),
 * narrowed here to the channel under test.
 */
class JourneyLogIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun logOf(channelSessionId: String): List<Map<String, Any?>> =
        (restTemplate.exchange(
            "http://localhost:$port/orchestrator/admin/journey-log", HttpMethod.GET, HttpEntity<Void>(adminHeaders()), mapType
        ).body!!["entries"] as List<Map<String, Any?>>).filter { it["channelSessionId"] == channelSessionId }

    init {
        given("a channel that ran a full registration") {
            `when`("reading that channel's journey log") {
                then("every step is recorded, newest first, grouped by channel/journey") {

                    // A real run, not a seeded account: this suite reads the journey LOG, which
                    // only an actual journey writes.
                    val channelSessionId = registerAndAuthenticate()

                    val entries = logOf(channelSessionId)

                    entries shouldHaveAtLeastSize 1
                    entries.all { it["channelType"] == "APP" } shouldBe true
                    entries.all { (it["intent"] as String).isNotBlank() } shouldBe true
                    entries.any { it["eventType"] == "Started" } shouldBe true
                    (entries.first()["createdAt"] as String).shouldNotBeBlank()

                    @Suppress("UNCHECKED_CAST")
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && (it["detail"] as Map<String, Any?>)["toolId"] == "ident-fsc" } shouldBe true
                    @Suppress("UNCHECKED_CAST")
                    entries.any { it["eventType"] == "Completed" && (it["detail"] as Map<String, Any?>)["toolId"] == "ident-fsc" } shouldBe true

                    // journeyState is a first-class field (like eventType), not tucked into detail.
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && it["journeyState"] != null } shouldBe true
                    entries.none { (it["detail"] as Map<*, *>).containsKey("state") } shouldBe true
                }
            }
        }

        given("a tool run that fails once before succeeding") {
            `when`("reading the journey log afterwards") {
                then("the failed attempt shows up as its own entry") {

                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                    patch(
                        "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                        """{"kvnr":"A123456789","name":"Muster","vorname":"Max","fsc":"WRONGCODE"}"""
                    )

                    logOf(channelSessionId).any { it["eventType"] == "TOOL_FAILED" } shouldBe true
                }
            }
        }

        given("an AUTHENTICATED channel with no journey currently running") {
            `when`("logging out") {
                then("the logout itself still shows up in the journey log") {

                    // A real run, not a seeded account: this suite reads the journey LOG, which
                    // only an actual journey writes.
                    val channelSessionId = registerAndAuthenticate()

                    post("/orchestrator/api/v1/channels/$channelSessionId/logouts")
                    post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                    val logoutEntry = logOf(channelSessionId).first { it["eventType"] == "LOGGED_OUT" }
                    logoutEntry["channelSessionId"] shouldBe channelSessionId
                    logoutEntry["channelType"] shouldBe "APP"
                }
            }
        }

        given("a registration whose channel is bound to an account only partway through") {
            `when`("reading its journey log") {
                then("the steps before the binding are attributed to that account too") {

                    val channelSessionId = registerAndAuthenticate()

                    val entries = logOf(channelSessionId)
                    val accountIds = entries.map { it["accountId"] }.toSet()
                    accountIds.size shouldBe 1
                    accountIds.single().shouldNotBeNull()
                    // "Started" is logged before ident-fsc binds the account - it still carries it.
                    entries.first { it["eventType"] == "Started" }["accountId"].shouldNotBeNull()
                }
            }
        }
    }
}
