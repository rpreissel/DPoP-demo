package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import kotlin.random.Random

/**
 * The register's own face (`/mock-stammdaten`, ADR-31) drives what ident-fsc accepts: a code the
 * register issues works right away, a revoked one stops working, and a changed name no longer
 * matches - without our side knowing the register was touched at all.
 */
class ExtStammdatenIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun registerCall(method: HttpMethod, path: String, body: String? = null): Map<String, Any?> {
        val headers = HttpHeaders().apply { set("Content-Type", "application/json") }
        return restTemplate.exchange("http://localhost:$port/mock-stammdaten$path", method, HttpEntity(body, headers), mapType).body.orEmpty()
    }

    /** A person of its own per scenario, so no other suite's seed code or throttle interferes. */
    private fun newPerson(): Pair<Long, String> {
        val kvnr = "Z" + (1..9).joinToString("") { Random.nextInt(10).toString() }
        val created = registerCall(
            HttpMethod.POST, "/personen",
            """{"kvnr":"$kvnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}"""
        )
        return (created["id"] as Number).toLong() to kvnr
    }

    private fun issue(personId: Long): Map<String, Any?> =
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
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"name":"Umbenannt","vorname":"Rita","geburtsdatum":"1970-01-01"}""")

                stepError(identifyWith(kvnr, "Register", code)).shouldNotBeNull()
            }

            then("a birthdate changed in the register no longer matches") {
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"name":"Register","vorname":"Rita","geburtsdatum":"1971-02-02"}""")

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

                val briefe = restTemplate.getForObject("http://localhost:$port/mock-stammdaten/briefe", List::class.java)!!
                briefe.any { (it as Map<*, *>)["code"] == code } shouldBe true
            }
        }

        given("a KVNR that is malformed or already registered") {
            then("the register refuses it with 409") {
                val (_, kvnr) = newPerson()
                listOf("""{"kvnr":"$kvnr"}""", """{"kvnr":"nope"}""").forEach { body ->
                    val refused = assertThrows<HttpClientErrorException> { registerCall(HttpMethod.POST, "/personen", body) }
                    refused.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }
    }
}
