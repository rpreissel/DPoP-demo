package com.example.dpop.orchestrator.kc

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review 2026-09, Phase F: made-up `kid`s must not turn every peer-auth request into a JWKS fetch
 * against Keycloak - an unknown kid refetches at most once per interval.
 */
class KeycloakJwkSourceBackoffTest : BehaviorSpec({

    given("a JWKS endpoint that counts its fetches") {
        val key = ECKeyGenerator(Curve.P_256).keyID("real").generate()
        val fetches = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                fetches.incrementAndGet()
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        afterSpec { server.stop(0) }
        val source = KeycloakJwkSource("http://127.0.0.1:${server.address.port}/jwks", cacheTtlSeconds = 600)

        then("a burst of unknown kids costs one fetch, and the known key is still found") {
            repeat(20) { source.find("made-up-$it") shouldBe null }
            source.find("real") shouldNotBe null
            fetches.get() shouldBe 1
        }
    }
})
