package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The operator endpoints behind the admin login (AdminSecurityConfig): locked without the right
 * credentials, and - once in - the log across all accounts, the account list, deletion and reset.
 * The app channel's own endpoints must stay reachable without any login.
 */
class AdminIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun adminGet(path: String, headers: HttpHeaders = adminHeaders()): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$path", HttpMethod.GET, HttpEntity<Void>(headers), mapType).body!!

    private fun adminList(path: String): List<Map<String, Any?>> =
        restTemplate.exchange(
            "http://localhost:$port$path", HttpMethod.GET, HttpEntity<Void>(adminHeaders()),
            object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}
        ).body!!

    private fun adminCall(method: HttpMethod, path: String): HttpStatus =
        restTemplate.exchange("http://localhost:$port$path", method, HttpEntity<Void>(adminHeaders()), Void::class.java).statusCode as HttpStatus

    init {
        given("the operator endpoints") {
            then("they answer 401 without credentials or with wrong ones, and without a browser login challenge") {
                val wrong = HttpHeaders().apply { setBasicAuth("admin", "falsch") }
                listOf(HttpHeaders(), wrong).forEach { headers ->
                    val refused = assertThrows<HttpClientErrorException> { adminGet("/orchestrator/admin/registration-order", headers) }
                    refused.statusCode shouldBe HttpStatus.UNAUTHORIZED
                    refused.responseHeaders?.getFirst(HttpHeaders.WWW_AUTHENTICATE) shouldBe null
                }
            }

            then("the app channel and the public server info need no login") {
                post("/orchestrator/api/v1/app/channels") // post() itself asserts the 2xx
                restTemplate.getForObject("http://localhost:$port/orchestrator/demo/server-info", Map::class.java)!!["keycloak"] shouldBe null
            }
        }

        given("an account created by a registration") {
            then("the log across all accounts attributes its journey, and the account can be listed and deleted") {
                identifyAndConfirmEmail()
                val accountId = adminList("/orchestrator/admin/accounts")
                    .single { it["displayName"] == "Max Muster" }["accountId"].let { (it as Number).toLong() }

                val log = adminGet("/orchestrator/admin/journey-log?limit=200")
                @Suppress("UNCHECKED_CAST")
                val entries = log["entries"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val accounts = log["accounts"] as List<Map<String, Any?>>
                entries.isNotEmpty() shouldBe true
                accounts.map { it["displayName"] } shouldContain "Max Muster"

                adminList("/orchestrator/admin/accounts").map { (it["accountId"] as Number).toLong() } shouldContain accountId
                adminCall(HttpMethod.DELETE, "/orchestrator/admin/accounts/$accountId") shouldBe HttpStatus.NO_CONTENT
                adminList("/orchestrator/admin/accounts").map { (it["accountId"] as Number).toLong() } shouldNotContain accountId
            }
        }

        given("an account that never ran a journey") {
            then("the log's person filter still offers it") {
                val accountId = seedRegisteredAccount()

                @Suppress("UNCHECKED_CAST")
                val accounts = adminGet("/orchestrator/admin/journey-log")["accounts"] as List<Map<String, Any?>>
                accounts.map { (it["accountId"] as Number).toLong() } shouldContain accountId
            }
        }

        given("a demo with accounts, a tool lock and the enroll-first order") {
            then("the reset removes the accounts and puts every setting back to its preset") {
                seedRegisteredAccount()
                put("/orchestrator/admin/tools/auth-sms/availability/APP", """{"enabled":false,"reason":"test"}""") shouldBe HttpStatus.OK
                put("/orchestrator/admin/registration-order", """{"enrollFirst":true}""") shouldBe HttpStatus.OK

                val reset = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/admin/demo-reset", HttpMethod.POST, HttpEntity<Void>(adminHeaders()), mapType
                )
                reset.statusCode shouldBe HttpStatus.OK
                // No keycloak profile here, so no demo accounts to put back.
                reset.body!!["seededAccounts"] shouldBe 0

                adminList("/orchestrator/admin/accounts") shouldBe emptyList()
                adminGet("/orchestrator/admin/registration-order")["enrollFirst"] shouldBe false
                @Suppress("UNCHECKED_CAST")
                val info = restTemplate.getForObject("http://localhost:$port/orchestrator/demo/server-info", Map::class.java)!!
                @Suppress("UNCHECKED_CAST")
                val locks = (info["disabledTools"] as List<Map<String, Any?>>).map { it["toolId"] to it["channel"] }
                // The ad-hoc lock is gone, the preset ones (demo.tool-defaults) are back.
                locks shouldNotContain ("auth-sms" to "APP")
                locks shouldContain ("auth-email" to "APP")
                locks shouldContain ("auth-device" to "KEYCLOAK")
            }
        }
    }
}
