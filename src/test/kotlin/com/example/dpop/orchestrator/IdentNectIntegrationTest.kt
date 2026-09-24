package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

/**
 * ident-nect end to end (docs/ideen/ident-nect.md): the app gets a jump URL, the user identifies
 * on Nect's page (here: its `/mock-nect` API), comes back with the case id, and the backend
 * redeems the result itself. Like ident-eid it attests and resolves nobody - binding to a
 * register person is ident-kvnr's step afterwards (ADR-18).
 */
class IdentNectIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }

        data class Started(val channelSessionId: String, val toolSessionId: String, val caseId: String)

        fun start(): Started {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val activated = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-nect")
            activated.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-nect", "step" to "redirect")
            val stepData = activated.stepData()
            stepData["kind"] shouldBe "nect-redirect"
            val caseId = stepData["caseId"] as String
            stepData["jumpUrl"] shouldBe "/nect/?case=$caseId"
            return Started(channelSessionId, activated.nextRaw()["toolSessionId"] as String, caseId)
        }

        val max = """"name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15""""
        val maxAddress = """"strasse":"Musterstraße 1","plz":"12345","ort":"Musterstadt""""

        /** What the jump page does when the user finishes there; answers where Nect sends the browser. */
        fun finishAtNect(caseId: String, procedure: String, attributes: String, pin: String? = null, expiryDate: String? = null): String {
            val pinField = pin?.let { ""","pin":"$it"""" } ?: ""
            val expiryField = expiryDate?.let { ""","expiryDate":"$it"""" } ?: ""
            return post("/mock-nect/cases/$caseId/result", """{"procedure":"$procedure","attributes":{$attributes}$pinField$expiryField}""")["redirectUri"] as String
        }

        fun report(toolSessionId: String, caseId: String) =
            patch("/orchestrator/api/v1/tools/$toolSessionId/ident-nect", """{"caseId":"$caseId"}""")

        fun evidenceJsonOf(channelSessionId: String): String = jdbcTemplate.queryForObject(
            """
            SELECT ae.amr_evidence FROM orchestrator.auth_evidence ae
            JOIN orchestrator.channel_session cs ON cs.auth_evidence_id = ae.id
            WHERE cs.id = CAST(? AS UUID)
            """,
            String::class.java,
            channelSessionId
        )!!

        fun claimedAttributesOf(channelSessionId: String): List<String> = jdbcTemplate.queryForList(
            """
            SELECT c.attribute_type FROM account.claim c
            JOIN orchestrator.channel_session cs ON cs.account_id = c.account_id
            WHERE cs.id = CAST(? AS UUID)
            """,
            String::class.java,
            channelSessionId
        )

        given("a registration identifying with the eID via Nect") {
            then("the return reports the case, the backend redeems it, and ident-kvnr follows") {
                val run = start()
                get("/mock-nect/cases/${run.caseId}")["requested"] shouldBe
                    listOf("family_name", "given_names", "birth_date", "address", "eid_pseudonym", "document_id")
                val redirectUri = finishAtNect(run.caseId, "eid", "$max,$maxAddress,\"restrictedId\":\"NECT-EID-MAX\"", pin = "123456")
                redirectUri shouldBe "/app/?nectCaseId=${run.caseId}"

                val attested = report(run.toolSessionId, run.caseId)

                attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")
                evidenceJsonOf(run.channelSessionId) shouldContain "nect-eid"
                val kvnrSession = post("/orchestrator/api/v1/channels/${run.channelSessionId}/tools/ident-kvnr").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$kvnrSession/ident-kvnr", """{"kvnr":"A123456789"}""")
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM account.anchor an
                    JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
                    WHERE cs.id = CAST(? AS UUID) AND an.attribute_type = 'person_id'
                    """,
                    Int::class.java,
                    run.channelSessionId
                ) shouldBe 1
            }
        }

        given("a registration identifying with a passport via Nect") {
            then("amr is nect-epass and no address is claimed - a passport carries none") {
                val run = start()
                finishAtNect(run.caseId, "epass", "$max,\"documentNumber\":\"C01X00T47\",\"issuingState\":\"D\"", expiryDate = "2099-01-01")

                report(run.toolSessionId, run.caseId)

                evidenceJsonOf(run.channelSessionId) shouldContain "nect-epass"
                val claimed = claimedAttributesOf(run.channelSessionId).joinToString()
                claimed shouldContain "name"
                claimed shouldNotContain "strasse"
            }
        }

        given("a passport whose chip spells the names its own way") {
            then("ident-kvnr still binds the register person - names compare in MRZ form") {
                val run = start()
                finishAtNect(
                    run.caseId, "epass",
                    """"name":"MUSTER","vorname":"MAX","geburtsdatum":"1985-06-15","documentNumber":"C01X00T47","issuingState":"D"""",
                    expiryDate = "2099-01-01"
                )
                report(run.toolSessionId, run.caseId)

                val kvnrSession = post("/orchestrator/api/v1/channels/${run.channelSessionId}/tools/ident-kvnr").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$kvnrSession/ident-kvnr", """{"kvnr":"A123456789"}""")

                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM account.anchor an
                    JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
                    WHERE cs.id = CAST(? AS UUID) AND an.attribute_type = 'person_id'
                    """,
                    Int::class.java,
                    run.channelSessionId
                ) shouldBe 1
            }
        }

        given("a returned case id that is not this run's") {
            then("it is refused, and the foreign case stays redeemable by its own run") {
                val mine = start()
                val other = start()
                finishAtNect(other.caseId, "eudi", max)

                val refused = report(mine.toolSessionId, other.caseId)

                refused.stepData()["kind"] shouldBe "failed-attempt"
                report(other.toolSessionId, other.caseId).next() shouldNotBe mapOf("type" to "tool", "toolId" to "ident-nect", "step" to "redirect")
                evidenceJsonOf(other.channelSessionId) shouldContain "nect-eudi"
            }
        }

        given("a case cancelled at Nect") {
            then("the report fails, and retry opens a fresh case") {
                val run = start()
                post("/mock-nect/cases/${run.caseId}/cancellation")["redirectUri"] as String shouldEndWith run.caseId

                report(run.toolSessionId, run.caseId).stepData()["kind"] shouldBe "failed-attempt"

                val retried = patch("/orchestrator/api/v1/tools/${run.toolSessionId}/ident-nect", """{"retry":true}""")
                retried.stepData()["kind"] shouldBe "nect-redirect"
                retried.stepData()["caseId"] shouldNotBe run.caseId
            }
        }
    }
}
