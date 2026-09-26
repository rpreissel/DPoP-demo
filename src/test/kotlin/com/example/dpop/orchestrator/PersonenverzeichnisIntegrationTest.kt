package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import kotlin.random.Random

/**
 * The register's own face (`/mock-personenverzeichnis`, ADR-31) drives what ident-fsc accepts: a code the
 * register issues works right away, a revoked one stops working, and a changed name no longer
 * matches - without our side knowing the register was touched at all.
 */
class PersonenverzeichnisIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun registerCall(method: HttpMethod, path: String, body: String? = null): Map<String, Any?> {
        val headers = HttpHeaders().apply { set("Content-Type", "application/json") }
        return restTemplate.exchange("http://localhost:$port/mock-personenverzeichnis$path", method, HttpEntity(body, headers), mapType).body.orEmpty()
    }

    private fun randomVersnr(): String = (10_000_000 + Random.nextInt(89_999_999)).toString()

    /**
     * A person of its own per scenario, so no other suite's seed code or throttle interferes -
     * insured with us, since only an insured person has a KVNR (ADR-34).
     */
    private fun newPerson(): Triple<String, String, String> {
        val kvnr = "Z" + (1..9).joinToString("") { Random.nextInt(10).toString() }
        val versnr = randomVersnr()
        val created = registerCall(
            HttpMethod.POST, "/personen",
            """{"kvnr":"$kvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}"""
        )
        return Triple(created["id"] as String, kvnr, versnr)
    }

    private fun issue(personId: String): Map<String, Any?> =
        registerCall(HttpMethod.POST, "/personen/$personId/freischaltcodes", """{"gueltigBis":"2099-01-01T00:00:00Z"}""")

    private fun identifyWith(kvnr: String, name: String, code: String): Map<String, Any?> {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        return patch(
            "/orchestrator/api/v1/tools/$toolSessionId/ident-fsc",
            """{"kvnr":"$kvnr","name":"$name","vorname":"Rita","geburtsdatum":"1970-01-01","fsc":"$code"}"""
        )
    }

    private fun stepError(response: Map<String, Any?>): Any? = (response["stepData"] as? Map<*, *>)?.get("error")

    init {
        given("a person the register has just created") {
            then("a freshly issued code identifies, and stops doing so once revoked") {
                val (personId, kvnr) = newPerson()
                val brief = issue(personId)
                val code = brief["code"] as String

                stepError(identifyWith(kvnr, "Register", code)).shouldBeNull()

                registerCall(HttpMethod.DELETE, "/freischaltcodes/${(brief["freischaltcodeId"] as Number).toLong()}")
                stepError(identifyWith(kvnr, "Register", code)).shouldNotBeNull()
            }

            then("a name changed in the register no longer matches") {
                val (personId, kvnr, versnr) = newPerson()
                val code = issue(personId)["code"] as String
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"$kvnr","versnr":"$versnr","name":"Umbenannt","vorname":"Rita","geburtsdatum":"1970-01-01"}""")

                stepError(identifyWith(kvnr, "Register", code)).shouldNotBeNull()
            }

            then("a birthdate changed in the register no longer matches") {
                val (personId, kvnr, versnr) = newPerson()
                val code = issue(personId)["code"] as String
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"$kvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1971-02-02"}""")

                stepError(identifyWith(kvnr, "Register", code)).shouldNotBeNull()
            }

            then("the demo persona picker offers the person with the code from its newest valid letter") {
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String

                val created = post("/orchestrator/api/v1/app/channels")
                val persons = (created["demo"] as Map<*, *>)["persons"] as List<*>
                val persona = persons.map { it as Map<*, *> }.single { it["kvnr"] == kvnr }
                persona["fscCode"] shouldBe code
                persona["name"] shouldBe "Register"
                persona["email"].shouldBeNull()
            }

            then("the mailbox holds the letter with the plaintext") {
                val (personId, _) = newPerson()
                val code = issue(personId)["code"] as String

                val briefe = restTemplate.getForObject("http://localhost:$port/mock-personenverzeichnis/briefe", List::class.java)!!
                briefe.any { (it as Map<*, *>)["code"] == code } shouldBe true
            }
        }

        given("a bound person whose KVNR and Versicherungsnummer change in the Personenverzeichnis") {
            then("the account follows via PersonChanged: new KVNR claim, Versicherungsnummer anchor set, replaced and released (ADR-34)") {
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String
                stepError(identifyWith(kvnr, "Register", code)).shouldBeNull()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = ?",
                    Long::class.java, personId
                )!!

                fun anchor(type: String): String? = jdbcTemplate.queryForList(
                    "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = ?",
                    String::class.java, accountId, type
                ).singleOrNull()

                fun eventually(check: () -> Boolean) {
                    val until = System.currentTimeMillis() + 10_000
                    while (!check()) {
                        check(System.currentTimeMillis() < until) { "account did not follow the Personenverzeichnis in time" }
                        Thread.sleep(100)
                    }
                }

                val versnr = randomVersnr()
                val newKvnr = "Y" + (1..9).joinToString("") { Random.nextInt(10).toString() }
                registerCall(
                    HttpMethod.PUT, "/personen/$personId",
                    """{"kvnr":"$newKvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}"""
                )
                eventually { anchor("versnr") == versnr }
                jdbcTemplate.queryForList(
                    """
                    SELECT c.normalized_value FROM account.claim c
                    WHERE c.account_id = ? AND c.attribute_type = 'kvnr' AND c.claim_source = 'person_directory'
                    AND NOT EXISTS (SELECT 1 FROM account.retraction r WHERE r.account_id = c.account_id
                        AND r.attribute_type = c.attribute_type AND r.normalized_value = c.normalized_value)
                    """.trimIndent(),
                    String::class.java, accountId
                ).map { it!!.uppercase() } shouldBe listOf(newKvnr)

                val replaced = randomVersnr()
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"$newKvnr","versnr":"$replaced","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}""")
                eventually { anchor("versnr") == replaced }

                // No longer insured with us: both numbers go, the person stays as a Partner.
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"","versnr":"","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}""")
                eventually { anchor("versnr") == null }
                anchor("person_id") shouldBe personId
            }
        }

        given("a Partner - no KVNR, no Versicherungsnummer, only the Partnernummer (ADR-34)") {
            then("a letter to the Partnernummer identifies via ident-fsc, and the account is bound without a KVNR") {
                val created = registerCall(HttpMethod.POST, "/personen", """{"name":"Partner","vorname":"Paul","geburtsdatum":"1960-06-06"}""")
                val personId = created["id"] as String
                personId shouldMatch Regex("^P\\d{9}$")
                created["kvnr"].shouldBeNull()
                val code = issue(personId)["code"] as String

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val response = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/ident-fsc",
                    """{"partnernr":"${personId.lowercase()}","name":"Partner","vorname":"Paul","geburtsdatum":"1960-06-06","fsc":"$code"}"""
                )

                stepError(response).shouldBeNull()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = ?",
                    Long::class.java, personId
                )!!
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'kvnr'", Int::class.java, accountId
                ) shouldBe 0
            }

            then("a KVNR without a Versicherungsnummer is refused") {
                val refused = assertThrows<HttpClientErrorException> {
                    registerCall(HttpMethod.POST, "/personen", """{"kvnr":"Z000000001","name":"Ohne","vorname":"Vertrag"}""")
                }
                refused.statusCode shouldBe HttpStatus.CONFLICT
            }
        }

        given("a KVNR that is malformed or already registered") {
            then("the register refuses it with 409") {
                val (_, kvnr) = newPerson()
                listOf("""{"kvnr":"$kvnr","versnr":"${randomVersnr()}"}""", """{"kvnr":"nope","versnr":"${randomVersnr()}"}""").forEach { body ->
                    val refused = assertThrows<HttpClientErrorException> { registerCall(HttpMethod.POST, "/personen", body) }
                    refused.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }
    }
}
