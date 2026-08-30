package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * The rich, per-step journey trace (docs/04-orchestrierung.md), distinct from the minimized
 * session_event audit trail - see JourneyLogEntry's own doc.
 */
class JourneyLogIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a channel that ran a full registration") {
            `when`("reading the journey log for that device's binding key") {
                then("every step is recorded, newest first, grouped by channel/journey") {

                    val channelSessionId = registerAndAuthenticate()

                    val log = get("/orchestrator/api/v1/journey-log")
                    @Suppress("UNCHECKED_CAST")
                    val entries = log["entries"] as List<Map<String, Any?>>

                    entries shouldHaveAtLeastSize 1
                    entries.all { it["channelSessionId"] == channelSessionId } shouldBe true
                    entries.all { (it["intent"] as String).isNotBlank() } shouldBe true
                    entries.any { it["eventType"] == "Started" } shouldBe true
                    (entries.first()["createdAt"] as String).shouldNotBeBlank()

                    @Suppress("UNCHECKED_CAST")
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && (it["detail"] as Map<String, Any?>)["toolId"] == "ident-fsc" } shouldBe true
                    @Suppress("UNCHECKED_CAST")
                    entries.any { it["eventType"] == "Completed" && (it["detail"] as Map<String, Any?>)["toolId"] == "ident-fsc" } shouldBe true
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

                    val log = get("/orchestrator/api/v1/journey-log")
                    @Suppress("UNCHECKED_CAST")
                    val entries = log["entries"] as List<Map<String, Any?>>
                    entries.any { it["eventType"] == "TOOL_FAILED" } shouldBe true
                }
            }
        }

        given("two different devices") {
            `when`("one of them reads the journey log") {
                then("only its own entries come back") {

                    registerAndAuthenticate()
                    val ownBindingKeyRef = currentBindingKeyRef

                    currentBindingKeyRef = "a-completely-different-binding-key"
                    post("/orchestrator/api/v1/app/channels")

                    currentBindingKeyRef = ownBindingKeyRef
                    val log = get("/orchestrator/api/v1/journey-log")
                    @Suppress("UNCHECKED_CAST")
                    val entries = log["entries"] as List<Map<String, Any?>>
                    entries shouldHaveAtLeastSize 1
                }
            }
        }
    }
}
