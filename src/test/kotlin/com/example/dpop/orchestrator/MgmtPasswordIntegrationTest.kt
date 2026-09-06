package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Covers the stateless, Channel/ToolSession-free `mgmt` endpoints backing Keycloak's native
 * password credential (docs/ideen/web-keycloak-kanal.md, `OrchestratorPasswordStorageProvider`).
 */
class MgmtPasswordIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(accountAnchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelAnchor = accountAnchor,
            subject = null
        )
    }

    private fun accountIdFor(email: String): Long =
        jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", Long::class.java, email)!!

    private fun mgmtPost(path: String, body: String): org.springframework.http.ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "http://localhost:$port$path",
            HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    set("Authorization", "Bearer mock-peer-auth-token")
                    set("Content-Type", "application/json")
                }
            ),
            mapType
        )

    init {
        Given("an account with an enrolled password") {
            When("mgmt-verify is called with the correct password, anchored to that accountId") {
                Then("it reports valid=true") {
                    val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                    val accountId = accountIdFor(email)
                    stubAssertion(accountAnchor = accountId.toString())

                    val response = mgmtPost(
                        "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        """{"password":"correct-horse-battery"}"""
                    )

                    response.statusCode shouldBe HttpStatus.OK
                    response.body!!["valid"] shouldBe true
                }
            }

            When("mgmt-verify is called with the wrong password") {
                Then("it reports valid=false") {
                    val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                    val accountId = accountIdFor(email)
                    stubAssertion(accountAnchor = accountId.toString())

                    val response = mgmtPost(
                        "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        """{"password":"wrong-password"}"""
                    )

                    response.body!!["valid"] shouldBe false
                }
            }

            When("mgmt-set is called with a new password") {
                Then("a later mgmt-verify accepts the new password and rejects the old one") {
                    val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                    val accountId = accountIdFor(email)
                    stubAssertion(accountAnchor = accountId.toString())

                    mgmtPost("/orchestrator/api/v1/tools/enroll-password/mgmt/$accountId", """{"newPassword":"brand-new-secret"}""")

                    stubAssertion(accountAnchor = accountId.toString())
                    val acceptsNew = mgmtPost(
                        "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        """{"password":"brand-new-secret"}"""
                    )
                    acceptsNew.body!!["valid"] shouldBe true

                    stubAssertion(accountAnchor = accountId.toString())
                    val rejectsOld = mgmtPost(
                        "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        """{"password":"correct-horse-battery"}"""
                    )
                    rejectsOld.body!!["valid"] shouldBe false
                }
            }
        }

        Given("an account with no password enrolled yet") {
            When("mgmt-verify is called against it") {
                Then("it reports valid=false without throwing (constant-shape, no enumeration oracle)") {
                    val channelSessionId = identify()
                    val accountId = jdbcTemplate.queryForObject(
                        "SELECT account_id FROM channel_session WHERE channel_session_id = ?",
                        Long::class.java,
                        UUID.fromString(channelSessionId)
                    )
                    stubAssertion(accountAnchor = accountId.toString())

                    val response = mgmtPost(
                        "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        """{"password":"anything"}"""
                    )

                    response.body!!["valid"] shouldBe false
                }
            }
        }

        Given("a mismatched peer-auth anchor") {
            When("mgmt-verify's channel_anchor claim doesn't match the accountId in the path") {
                Then("it is rejected as unauthorized (same contract as a missing/invalid peer-auth assertion)") {
                    val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                    val accountId = accountIdFor(email)
                    stubAssertion(accountAnchor = "some-other-anchor")

                    val rejected = assertThrows<HttpClientErrorException> {
                        restTemplate.exchange(
                            "http://localhost:$port/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                            HttpMethod.POST,
                            HttpEntity(
                                """{"password":"correct-horse-battery"}""",
                                HttpHeaders().apply {
                                    set("Authorization", "Bearer mock-peer-auth-token")
                                    set("Content-Type", "application/json")
                                }
                            ),
                            mapType
                        )
                    }
                    rejected.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }
    }
}
