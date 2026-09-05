package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.kc.PeerAuthAssertion
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/** Covers the kc-facade's one facade-specific endpoint (docs/ideen/web-keycloak-kanal.md #6). */
class KcChannelIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(channelAnchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelAnchor = channelAnchor,
            subject = null
        )
    }

    private fun kcPatchRaw(channelSessionId: UUID, body: String = "{}") =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
            HttpMethod.PATCH,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    set("Authorization", "Bearer mock-peer-auth-token")
                    set("Content-Type", "application/json")
                }
            ),
            mapType
        )

    private fun kcPatch(channelSessionId: UUID, body: String = "{}"): Map<String, Any?> =
        kcPatchRaw(channelSessionId, body).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Same peer-auth-bearing headers, for the facade-neutral tool endpoints (docs/ideen/web-keycloak-kanal.md #6). */
    private fun kcHeaders(): HttpHeaders = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcPost(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    private fun kcPatchTool(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    init {
        Given("a fresh Keycloak-chosen channelSessionId, no channel yet") {
            When("PATCH is called with a kcAuthSessionId anchor (initial login)") {
                Then("it creates the channel and offers the initial-login candidates") {
                    val channelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-auth-session-${UUID.randomUUID()}")
                    val response = kcPatch(channelSessionId)

                    response.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    response.channel()["channelType"] shouldBe "KEYCLOAK"
                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    response["authData"] shouldBe mapOf<String, Any?>()
                }
            }

            When("PATCH is called twice with the same id and anchor") {
                Then("the second call resumes the very same channel (idempotent upsert)") {
                    val channelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-auth-session-${UUID.randomUUID()}")
                    val first = kcPatch(channelSessionId)
                    val second = kcPatch(channelSessionId)

                    first.channel()["channelSessionId"] shouldBe second.channel()["channelSessionId"]
                }
            }

            When("a later PATCH on the same id presents a different kc-anchor") {
                Then("it is rejected as a binding mismatch") {
                    val channelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-auth-session-${UUID.randomUUID()}")
                    kcPatch(channelSessionId)
                    stubAssertion(channelAnchor = "a-completely-different-anchor")

                    val rejected = assertThrows<HttpClientErrorException> { kcPatchRaw(channelSessionId) }
                    rejected.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            When("the Authorization header is missing") {
                Then("it is rejected as unauthorized") {
                    val rejected = assertThrows<HttpClientErrorException> {
                        restTemplate.exchange(
                            "http://localhost:$port/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}",
                            HttpMethod.PATCH,
                            HttpEntity("{}", HttpHeaders().apply { set("Content-Type", "application/json") }),
                            mapType
                        )
                    }
                    rejected.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }

            When("accountId names an account that doesn't exist") {
                Then("it is rejected up front as not found, never as an internal strategy error") {
                    stubAssertion(channelAnchor = "kc-user-session-${UUID.randomUUID()}")
                    val rejected = assertThrows<HttpClientErrorException> {
                        kcPatchRaw(UUID.randomUUID(), """{"accountId":999999,"amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"}]}""")
                    }
                    rejected.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        Given("a step-up call naming an account Keycloak already knows") {
            When("PATCH is called with accountId and targetAcr") {
                Then("it binds the channel to that account and offers its auth candidates") {
                    val authenticatedChannelSessionId = registerAndAuthenticate()
                    val accountId = jdbcTemplate.queryForObject(
                        "SELECT account_id FROM channel_session WHERE channel_session_id = ?",
                        Long::class.java,
                        UUID.fromString(authenticatedChannelSessionId)
                    )

                    val kcChannelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-user-session-${UUID.randomUUID()}")
                    val response = kcPatch(kcChannelSessionId, """{"accountId":$accountId,"targetAcr":"loa2"}""")

                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    (response["authData"] as Map<*, *>)["accountId"] shouldBe accountId
                    @Suppress("UNCHECKED_CAST")
                    val options = response.stepData()["options"] as List<String>
                    // targetAcr's real job (docs/ideen/web-keycloak-kanal.md #6/#9): candidates
                    // for an already-known account go through CandidateTools.forAuth alone, never
                    // forIdentification - an already-authenticated account is never re-offered fsc.
                    options shouldNotContain "ident-fsc"
                    // AuthPolicy.candidateTools (shared with the App channel's own STEP_UP) already
                    // filters to exactly this account's own active methods - registerAndAuthenticate
                    // only ever enrolled sms+email, so password/device must never appear here even
                    // though they're both in the catalog.
                    options shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-email")
                }
            }
        }

        Given("a kc channel offering auth-password-lookup as its initial-login candidate") {
            When("the facade-neutral tool endpoints are driven with peer-auth instead of DPoP") {
                Then("lookup-based login completes through them exactly like the App channel's own flow") {
                    // Seeded via the App channel (DPoP) - only the kc-side calls below use peer-auth.
                    val email = registerWithEmailAndPassword()

                    val channelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-auth-session-${UUID.randomUUID()}")
                    val initial = kcPatch(channelSessionId)
                    @Suppress("UNCHECKED_CAST")
                    (initial.stepData()["options"] as List<String>) shouldNotContain "ident-fsc"

                    val toolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
                        .nextRaw()["toolSessionId"] as String
                    val completed = kcPatchTool(
                        "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                        """{"email":"$email","password":"correct-horse-battery"}"""
                    )

                    completed.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    (completed["authData"] as Map<*, *>)["accountId"] shouldNotBe null
                    // A completed orchestrator tool tags its amr with "orchestrator", never "kc".
                    @Suppress("UNCHECKED_CAST")
                    val amr = (completed["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    amr shouldBe mapOf("password" to "orchestrator")
                }
            }

            When("a later PATCH re-reports the same method via amr (a naive full-list resend)") {
                Then("its source stays orchestrator - the stronger, verified claim is never downgraded to kc") {
                    val email = registerWithEmailAndPassword()
                    val channelSessionId = UUID.randomUUID()
                    val anchor = "kc-auth-session-${UUID.randomUUID()}"
                    stubAssertion(channelAnchor = anchor)
                    kcPatch(channelSessionId)
                    val toolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
                        .nextRaw()["toolSessionId"] as String
                    kcPatchTool(
                        "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                        """{"email":"$email","password":"correct-horse-battery"}"""
                    )

                    // Same channel, same anchor - Keycloak (or a naive mock) resends "password" as
                    // if it were native evidence too.
                    stubAssertion(channelAnchor = anchor)
                    val resumed = kcPatch(channelSessionId, """{"amr":[{"nativeToolId":"kc-password-form","amrSourceId":"kc-password-form-exec-1"}]}""")

                    @Suppress("UNCHECKED_CAST")
                    val amr = (resumed["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    amr shouldBe mapOf("password" to "orchestrator")
                }
            }
        }

        Given("a step-up channel whose account already reaches loa1 evidence natively") {
            When("PATCH is called again with amr (Mock-Keycloak: simulate a native authenticator)") {
                Then("the merged evidence is reflected in authData and, once sufficient, authenticates") {
                    val authenticatedChannelSessionId = registerAndAuthenticate()
                    val accountId = jdbcTemplate.queryForObject(
                        "SELECT account_id FROM channel_session WHERE channel_session_id = ?",
                        Long::class.java,
                        UUID.fromString(authenticatedChannelSessionId)
                    )

                    val kcChannelSessionId = UUID.randomUUID()
                    stubAssertion(channelAnchor = "kc-user-session-${UUID.randomUUID()}")
                    // A raised loa2 floor - sms alone (capped at loa1) doesn't satisfy it yet, so
                    // this still offers candidates, but authData already reflects the native evidence.
                    val partial = kcPatch(
                        kcChannelSessionId,
                        """{"accountId":$accountId,"targetAcr":"loa2","amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"}]}"""
                    )
                    (partial["authData"] as Map<*, *>)["acr"] shouldBe "loa1"
                    // amr is method -> source (docs/ideen/web-keycloak-kanal.md #8), never a bare
                    // list - "sms" was proven by a simulated native authenticator, not a tool.
                    @Suppress("UNCHECKED_CAST")
                    val partialAmr = (partial["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    partialAmr shouldBe mapOf("sms" to "kc")

                    // A second native authenticator (password, KNOWLEDGE - a different factor type
                    // than sms's POSSESSION) closes the loa2 gap via MFA: two distinct native
                    // factor types combine exactly like two orchestrator ones would
                    // (DefaultAuthPolicy's bump, deliberately not capped down for kc evidence -
                    // see KcChannelService). `amr` is the caller's COMPLETE currently-valid kc set
                    // for THIS call (docs/ideen/web-keycloak-kanal.md #6/#9, AuthEvidence.
                    // replaceForSource) - a real Authenticator's own AmrUtils-style computation
                    // would resend "sms" here too if it is still valid, not just the newest proof;
                    // omitting it would mean it expired.
                    val authenticated = kcPatch(
                        kcChannelSessionId,
                        """{"accountId":$accountId,"amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"},{"nativeToolId":"kc-password-form","amrSourceId":"kc-password-form-exec-1"}]}"""
                    )
                    authenticated.channel()["state"] shouldBe "AUTHENTICATED"
                    @Suppress("UNCHECKED_CAST")
                    val finalAmr = (authenticated["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    finalAmr shouldBe mapOf("sms" to "kc", "password" to "kc")
                }
            }
        }

        Given("an authenticated App channel") {
            When("the channel is resumed") {
                Then("its response never carries authData - authData is KEYCLOAK-only") {
                    val channelSessionId = registerAndAuthenticate()

                    get("/orchestrator/api/v1/channels/$channelSessionId").containsKey("authData") shouldBe false
                }
            }
        }
    }
}
