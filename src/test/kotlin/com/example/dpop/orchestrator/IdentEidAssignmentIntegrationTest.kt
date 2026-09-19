package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The two acts of an eID run (docs/12-entscheidungen.md ADR-18): `ident-eid` attests what the
 * card shows and resolves nobody, then an explicit question decides whether `ident-kvnr` binds
 * the register's person on top. Declining is a first-class outcome, not a failure: the account
 * stays an Interessent (ADR-10) with a fully attested identity behind it.
 */
class IdentEidAssignmentIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }

        /** Card read plus PIN - the whole tool, with nothing typed to look anybody up first. */
        fun attestViaEid(channelSessionId: String): Map<String, Any?> {
            val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-eid")
                .nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/ident-eid",
                """{"name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","strasse":"Musterstraße","hausnummer":"1","plz":"12345","ort":"Musterstadt","restrictedId":"T0103005K1D5S0V8T9W6UM2RTX"}"""
            )
            return patch("/orchestrator/api/v1/tools/$toolSessionId/ident-eid", """{"pin":"123456"}""")
        }

        /** How many PERSON_ID anchors the channel's account has - 0 for an Interessent, 1 once bound. */
        fun personAnchorsOf(channelSessionId: String): Int = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM account.anchor an
            JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
            WHERE cs.id = CAST(? AS UUID) AND an.attribute_type = 'person_id'
            """,
            Int::class.java,
            channelSessionId
        )!!

        given("a fresh channel starting a registration") {
            then("the identification choice does not offer the correlation step - there is nothing attested to correlate yet") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val next = get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()

                // Either a selection page or a single-candidate skip - both must exclude ident-kvnr.
                @Suppress("UNCHECKED_CAST")
                val options = get("/orchestrator/api/v1/channels/$channelSessionId").stepData()["options"] as? List<String>
                (options ?: listOf(next["toolId"] as String)) shouldNotContain "ident-kvnr"
            }
        }

        given("an eID attestation that resolved nobody") {
            `when`("the assignment question is declined") {
                then("the run carries on and the account stays an Interessent") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                    val attested = attestViaEid(channelSessionId)
                    attested.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                    val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                    // The registration continues where it always does - the address step.
                    (declined.nextRaw()["toolId"] ?: declined.nextRaw()["context"]) shouldBe "confirm-email"

                    personAnchorsOf(channelSessionId) shouldBe 0
                }
            }

            `when`("the assignment question is accepted and a matching KVNR is supplied") {
                then("saying yes leads straight to the tool, and it binds the register's person to the very same account") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(channelSessionId)

                    // Following `next` is the whole point: the client only ever goes where the
                    // backend points. A prompt that answers itself with the same prompt would
                    // leave the user stuck on a button that does nothing.
                    val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                    accepted.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")

                    // `next` points AT the tool; the client still activates it explicitly, like
                    // every other tool - the answer response creates no tool session of its own.
                    val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-kvnr")
                        .nextRaw()["toolSessionId"] as String
                    patch("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr", """{"kvnr":"A123456789"}""")

                    personAnchorsOf(channelSessionId) shouldBe 1
                }
            }

            `when`("the assignment question is accepted but somebody else's KVNR is supplied") {
                then("it is refused - the register's person contradicts the attested identity") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(channelSessionId)

                    val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                    val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-kvnr")
                        .nextRaw()["toolSessionId"] as String
                    val conflict = assertThrows<HttpClientErrorException> {
                        patch("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr", """{"kvnr":"B987654321"}""")
                    }

                    conflict.statusCode shouldBe HttpStatus.CONFLICT
                    personAnchorsOf(channelSessionId) shouldBe 0
                }
            }
        }
    }
}
