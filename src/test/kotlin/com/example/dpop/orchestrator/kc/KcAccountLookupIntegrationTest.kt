package com.example.dpop.orchestrator.kc

import com.example.dpop.orchestrator.IntegrationTestSupport
import com.example.dpop.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.UUID

/**
 * Review 2026-09, P-3: Keycloak reads accounts instead of mirroring them - one indexed read by id,
 * email or username, never a list; each lookup names what it looks up in its peer-auth anchor.
 */
class KcAccountLookupIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    private fun anchor(value: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns
            PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelAnchor = value, subject = null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun lookup(path: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/$path", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer peer-auth") }), mapType
        ).body!!

    private fun status(path: String): HttpStatus =
        try {
            restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/kc/$path", HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer peer-auth") }), String::class.java
            ).statusCode as HttpStatus
        } catch (e: HttpClientErrorException) {
            e.statusCode as HttpStatus
        }

    init {
        given("an identified account with a confirmed address") {
            then("Keycloak finds it by id, by email and by username - with names, attributes and the account id") {
                val accountId = accountFixtures.seedAccount()

                anchor(accountId.toString())
                val byId = lookup("accounts/$accountId")
                byId["accountId"] shouldBe accountId.toInt()
                byId["email"] shouldBe AccountFixtures.EMAIL
                byId["username"] shouldBe AccountFixtures.EMAIL
                byId["emailVerified"] shouldBe true
                byId["firstName"] shouldNotBe null
                @Suppress("UNCHECKED_CAST")
                (byId["attributes"] as Map<String, Any?>)["orchestratorAccountId"] shouldBe accountId.toString()

                anchor("account-lookup")
                lookup("accounts?email=${AccountFixtures.EMAIL}")["accountId"] shouldBe accountId.toInt()
                lookup("accounts?username=${AccountFixtures.EMAIL}")["accountId"] shouldBe accountId.toInt()
                lookup("accounts?username=account-$accountId")["accountId"] shouldBe accountId.toInt()
            }
        }

        given("an account without an address") {
            then("its username is account-<id>") {
                val accountId = accountFixtures.seedAccount(email = null)
                anchor(accountId.toString())
                lookup("accounts/$accountId")["username"] shouldBe "account-$accountId"
            }
        }

        given("lookups that must not answer") {
            then("unknown accounts are 404, a mismatched anchor is refused, and there is no list") {
                anchor("999999")
                status("accounts/999999") shouldBe HttpStatus.NOT_FOUND
                anchor("account-lookup")
                status("accounts?email=nobody@example.com") shouldBe HttpStatus.NOT_FOUND
                status("accounts") shouldBe HttpStatus.BAD_REQUEST

                val accountId = accountFixtures.seedAccount()
                anchor("account-lookup")
                (status("accounts/$accountId").value() in 400..499) shouldBe true
            }
        }
    }
}
