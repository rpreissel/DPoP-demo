package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain as shouldNotContainText
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
                """{"name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15","strasse":"Musterstraße 1","plz":"12345","ort":"Musterstadt","restrictedId":"T0103005K1D5S0V8T9W6UM2RTX"}"""
            )
            return patch("/orchestrator/api/v1/tools/$toolSessionId/ident-eid", """{"pin":"123456"}""")
        }

        /** The second demo person's card - used where a test needs a persona Max's fixtures don't already own. */
        fun attestAsErika(channelSessionId: String): Map<String, Any?> {
            val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-eid")
                .nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/ident-eid",
                """{"name":"Beispiel","vorname":"Erika","geburtsdatum":"1990-11-02","strasse":"Beispielweg 42","plz":"54321","ort":"Beispielhausen","restrictedId":"T0208011X7Y2Q4M6B3LT0T28WJ"}"""
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

        /**
         * The raw `amr_evidence` JSON this channel has accumulated - what `resolveAcr` prices
         * from. Read from the evidence row rather than a response field so the assertion holds
         * whatever a given endpoint chooses to surface.
         */
        fun evidenceJsonOf(channelSessionId: String): String = jdbcTemplate.queryForObject(
            """
            SELECT ae.amr_evidence FROM orchestrator.auth_evidence ae
            JOIN orchestrator.channel_session cs ON cs.auth_evidence_id = ae.id
            WHERE cs.id = CAST(? AS UUID)
            """,
            String::class.java,
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

        /** Runs confirm-email to completion for one specific address on an already-running journey. */
        fun confirmAddress(channelSessionId: String, email: String): Map<String, Any?> {
            val confirmSession = post("/orchestrator/api/v1/channels/$channelSessionId/tools/confirm-email")
                .nextRaw()["toolSessionId"] as String
            val (code, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$confirmSession/confirm-email", """{"email":"$email"}""")
            }
            return patch("/orchestrator/api/v1/tools/$confirmSession/confirm-email", """{"code":"$code"}""")
        }

        // ADR-20 through the EMAIL anchor: confirming an address proves possession of a value the
        // account model resolves accounts BY, so the provisional account goes into the one that
        // already holds it - which is how somebody who skipped the assignment step still lands on
        // their own account instead of in a dead end at their own address.
        given("an Interessent confirming an address that belongs to their own account") {
            then("the provisional account goes into it, and the run continues there") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestAsErika(channelSessionId)
                val provisional = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }
                delete("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr")

                confirmAddress(channelSessionId, "erika.beispiel@example.com")

                accountIdOf(channelSessionId) shouldBe existing
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.account WHERE id = ?", Int::class.java, provisional
                ) shouldBe 0
                // The attestation came along - her card now recognizes this account (ADR-19).
                restrictedIdAnchorsOf(existing) shouldBe 1
            }
        }

        given("an Interessent confirming an address that belongs to somebody else") {
            then("it is refused - holding a mailbox does not make you that person") {
                accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                // Max's card, Erika's address.
                attestViaEid(channelSessionId)
                val provisional = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }
                delete("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr")

                val conflict = assertThrows<HttpClientErrorException> {
                    confirmAddress(channelSessionId, "erika.beispiel@example.com")
                }

                conflict.statusCode shouldBe HttpStatus.CONFLICT
                accountIdOf(channelSessionId) shouldBe provisional
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
                    "SELECT COUNT(*) FROM account.change_log WHERE account_id = ? AND change_type = 'IDENTIFIED' AND subject = 'eid'",
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
                    // Both acts are audited, and each row says which act it was - a correlation
                    // step carries the attestation's level, so without the role a `kvnr / loa2`
                    // row would read like a procedure that reached loa2 by itself.
                    jdbcTemplate.queryForList(
                        """
                        SELECT e.subject FROM account.change_log e
                        JOIN orchestrator.channel_session cs ON cs.account_id = e.account_id
                        WHERE cs.id = CAST(? AS UUID) AND e.change_type = 'IDENTIFIED' ORDER BY e.occurred_at
                        """,
                        String::class.java,
                        channelSessionId
                    ) shouldBe listOf("eid", "kvnr")
                    jdbcTemplate.queryForObject(
                        """
                        SELECT CAST(e.details AS VARCHAR) FROM account.change_log e
                        JOIN orchestrator.channel_session cs ON cs.account_id = e.account_id
                        WHERE cs.id = CAST(? AS UUID) AND e.change_type = 'IDENTIFIED' AND e.subject = 'kvnr'
                        """,
                        String::class.java,
                        channelSessionId
                    )!! shouldContainText "CORRELATION"
                }
            }

            then("the correlation step leaves no session evidence - it proves nothing, so it prices nothing") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                val toolSessionId = activateAssignment(channelSessionId)
                patch("/orchestrator/api/v1/tools/$toolSessionId/ident-kvnr", """{"kvnr":"A123456789"}""")

                // The attestation ahead of it is on the IDENTITY axis and stays there; the
                // correlation adds nothing of its own. Were `kvnr` recorded, its own loa2 would
                // become an assurance level bought by typing a semi-public number
                // (ToolDescriptor.evidenceAxis) - and on a path where the attestation reached
                // less than loa2, it would be the level the session rests on.
                val evidence = evidenceJsonOf(channelSessionId)
                evidence shouldContainText "eid"
                evidence shouldNotContainText "kvnr"
                personAnchorsOf(channelSessionId) shouldBe 1
            }

            `when`("the account already has a person bound") {
                then("the correlation step is not offered a second time") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(channelSessionId)
                    val first = activateAssignment(channelSessionId)
                    patch("/orchestrator/api/v1/tools/$first/ident-kvnr", """{"kvnr":"A123456789"}""")
                    personAnchorsOf(channelSessionId) shouldBe 1

                    // Attaching a person to an account that already has one is a change of
                    // identity bought with no proof at all, so the step stops being offered the
                    // moment the anchor exists. This asserts the offer layer, which is as far as
                    // a client can get: JourneyActionExecutor refuses the same case a second time,
                    // but only as a backstop for a future strategy that might offer it anyway -
                    // unreachable, and deliberately so, while this 409 stands.
                    val refused = assertThrows<HttpClientErrorException> { activateAssignment(channelSessionId) }
                    refused.statusCode shouldBe HttpStatus.CONFLICT
                    personAnchorsOf(channelSessionId) shouldBe 1
                }
            }

            `when`("somebody else's KVNR is supplied") {
                then("it is refused exactly like an unknown KVNR - the answer reveals nothing (review 2026-09, S-6)") {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(channelSessionId)
                    val foreign = patch(
                        "/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr",
                        """{"kvnr":"B987654321"}"""
                    )
                    personAnchorsOf(channelSessionId) shouldBe 0

                    val otherChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    attestViaEid(otherChannel)
                    val unknown = patch(
                        "/orchestrator/api/v1/tools/${activateAssignment(otherChannel)}/ident-kvnr",
                        """{"kvnr":"X999999999"}"""
                    )

                    foreign.next() shouldBe unknown.next()
                    foreign["stepData"] shouldBe unknown["stepData"]
                    foreign.channel()["state"] shouldBe unknown.channel()["state"]
                }
            }
        }
    }
}
