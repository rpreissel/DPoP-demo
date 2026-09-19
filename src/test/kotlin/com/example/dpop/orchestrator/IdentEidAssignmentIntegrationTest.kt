package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The two acts of an eID run (docs/12-entscheidungen.md ADR-18): `ident-eid` attests what the
 * card shows and resolves nobody, then `ident-kvnr` is offered to bind the register's person on
 * top. The step is offered directly - no Ja/Nein prompt in front of it - and abandoning it is a
 * first-class outcome, not a failure: the account stays an Interessent (ADR-10) with a fully
 * attested identity behind it.
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

        /** The second demo person's card - used where a test needs a persona Max's fixtures don't already own. */
        fun attestAsErika(channelSessionId: String): Map<String, Any?> {
            val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-eid")
                .nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/ident-eid",
                """{"name":"Beispiel","vorname":"Erika","geburtsdatum":"1990-11-02","strasse":"Beispielweg","hausnummer":"42","plz":"54321","ort":"Beispielhausen","restrictedId":"T0208011X7Y2Q4M6B3LT0T28WJ"}"""
            )
            return patch("/orchestrator/api/v1/tools/$toolSessionId/ident-eid", """{"pin":"123456"}""")
        }

        /** Activates the correlation step the attestation left `next` pointing at. */
        fun activateAssignment(channelSessionId: String): String =
            post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-kvnr").nextRaw()["toolSessionId"] as String

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

        /** The account the channel currently points at - null once it points at none. */
        fun accountIdOf(channelSessionId: String): Long? = jdbcTemplate.queryForObject(
            "SELECT account_id FROM orchestrator.channel_session WHERE id = CAST(? AS UUID)",
            Long::class.java,
            channelSessionId
        )

        /** How many restricted_id anchors this account holds - the eid attestation's own anchor (ADR-19). */
        fun restrictedIdAnchorsOf(accountId: Long): Int = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
            Int::class.java,
            accountId
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

        // ADR-20: the assignment step resolves an account that already exists, while the run
        // holds only the placeholder the eID attestation itself created. The user did nothing
        // wrong - the journey built its own conflict - so the placeholder yields.
        // ADR-19's recognition seen from the journey: once an account holds a card's
        // restricted_id, the next run with that card resolves onto it instead of registering a
        // second, parallel account beside it.
        given("a card whose restricted_id an existing account already holds") {
            then("the run is recognized onto that account instead of registering a new one") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password()),
                    restrictedId = "T0208011X7Y2Q4M6B3LT0T28WJ"
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                attestAsErika(channelSessionId)

                accountIdOf(channelSessionId) shouldBe existing
                // Her account is complete, so the run offers her own methods rather than a
                // registration - and never asks for the KVNR of an account already bound.
                @Suppress("UNCHECKED_CAST")
                val options = get("/orchestrator/api/v1/channels/$channelSessionId").stepData()["options"] as? List<String>
                val offered = options ?: listOf(get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()["toolId"] as String)
                offered shouldContain "auth-sms"
                offered shouldNotContain "ident-kvnr"
            }
        }

        given("an eID attestation whose KVNR belongs to an account that already exists") {
            then("the provisional account yields, the run continues on the existing one") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "A123456789", name = "Muster", vorname = "Max",
                    methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                val provisional = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }
                provisional shouldNotBe existing

                val toolSessionId = activateAssignment(channelSessionId)
                val assigned = patch("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr", """{"kvnr":"A123456789"}""")

                // The run moved over, and the placeholder is gone rather than left as a stray.
                accountIdOf(channelSessionId) shouldBe existing
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.account WHERE id = ?", Int::class.java, provisional
                ) shouldBe 0
                // The attestation came along - the card's own anchor now recognizes this account.
                restrictedIdAnchorsOf(existing) shouldBe 1
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.identification WHERE account_id = ? AND method = 'eid'",
                    Int::class.java, existing
                ) shouldBe 1

                // And it lands where every other route to an existing account lands: prove one of
                // its methods, not enroll a new one on top (RegisterStrategy.afterIdentification).
                @Suppress("UNCHECKED_CAST")
                val options = get("/orchestrator/api/v1/channels/$channelSessionId").stepData()["options"] as? List<String>
                (options ?: listOf(assigned.nextRaw()["toolId"] as String)) shouldContain "auth-sms"
            }
        }

        given("an eID attestation that resolved nobody") {
            `when`("the assignment step is abandoned - the 'jetzt nicht' of this flow") {
                then("the run carries on and the account stays an Interessent") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                    // No prompt in between: the attestation points straight at the correlation tool.
                    val attested = attestViaEid(channelSessionId)
                    attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")

                    val toolSessionId = activateAssignment(channelSessionId)
                    val skipped = delete("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr")
                    // The registration continues where it always does - the address step.
                    (skipped.nextRaw()["toolId"] ?: skipped.nextRaw()["context"]) shouldBe "confirm-email"

                    personAnchorsOf(channelSessionId) shouldBe 0
                }
            }

            `when`("a matching KVNR is supplied") {
                then("it binds the register's person to the very same account") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    val attested = attestViaEid(channelSessionId)

                    // Following `next` is the whole point: the client only ever goes where the
                    // backend points - and it now points AT the tool, not at a question about it.
                    attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")
                    val toolSessionId = activateAssignment(channelSessionId)
                    patch("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr", """{"kvnr":"A123456789"}""")

                    personAnchorsOf(channelSessionId) shouldBe 1
                }
            }

            `when`("somebody else's KVNR is supplied") {
                then("it is refused - the register's person contradicts the attested identity") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(channelSessionId)

                    val toolSessionId = activateAssignment(channelSessionId)
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
