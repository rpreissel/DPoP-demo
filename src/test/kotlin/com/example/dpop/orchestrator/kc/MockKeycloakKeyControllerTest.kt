package com.example.dpop.orchestrator.kc

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate

/** Demo-only key exchange backing the Mock-Keycloak frontend (bd DPoP-demo-f9o.9). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MockKeycloakKeyControllerTest : BehaviorSpec() {

    @LocalServerPort
    private var port: Int = 0

    private val restTemplate = RestTemplate()
    private val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    init {
        Given("the mock-keycloak key endpoints") {
            When("the public JWKS is fetched") {
                Then("it carries exactly one EC public key, no private material") {
                    val response = restTemplate.exchange(
                        "http://localhost:$port/mock-keycloak/.well-known/jwks.json",
                        org.springframework.http.HttpMethod.GET, null, mapType
                    )
                    response.statusCode shouldBe HttpStatus.OK
                    @Suppress("UNCHECKED_CAST")
                    val keys = response.body!!["keys"] as List<Map<String, Any?>>
                    keys.size shouldBe 1
                    keys[0]["kty"] shouldBe "EC"
                    keys[0].containsKey("d") shouldBe false
                }
            }

            When("the private signing key is fetched") {
                Then("it carries the private 'd' component matching the public key's kid") {
                    val jwksResponse = restTemplate.exchange(
                        "http://localhost:$port/mock-keycloak/.well-known/jwks.json",
                        org.springframework.http.HttpMethod.GET, null, mapType
                    )
                    @Suppress("UNCHECKED_CAST")
                    val publicKid = ((jwksResponse.body!!["keys"] as List<Map<String, Any?>>)[0])["kid"]

                    val response = restTemplate.exchange(
                        "http://localhost:$port/mock-keycloak/signing-key",
                        org.springframework.http.HttpMethod.GET, null, mapType
                    )
                    response.statusCode shouldBe HttpStatus.OK
                    response.body!!["kid"] shouldBe publicKid
                    response.body!!.containsKey("d") shouldBe true
                }
            }
        }
    }
}
