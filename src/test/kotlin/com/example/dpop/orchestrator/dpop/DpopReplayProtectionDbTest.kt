package com.example.dpop.orchestrator.dpop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Single use against the real table (review 2026-09-26, T-3): the primary key IS the replay check,
 * so a second proof with the same `thumbprint:jti` must fail - one after the other, and when
 * several arrive at the same moment.
 */
@SpringBootTest
@ActiveProfiles("test")
class DpopReplayProtectionDbTest(private val service: DpopReplayProtectionService) : BehaviorSpec({

    val expiresAt = Instant.now().plusSeconds(300)

    given("a proof that was already accepted") {
        then("the same thumbprint and jti are refused the second time") {
            val jti = UUID.randomUUID().toString()
            service.validateAndStore("thumb", jti, expiresAt)
            shouldThrow<DpopValidationException> { service.validateAndStore("thumb", jti, expiresAt) }
        }
    }

    given("the same proof sent eight times at once") {
        then("exactly one is accepted") {
            val jti = UUID.randomUUID().toString()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val results = (1..8).map {
                    pool.submit(Callable {
                        start.await()
                        runCatching { service.validateAndStore("thumb", jti, expiresAt) }.isSuccess
                    })
                }
                start.countDown()
                results.count { it.get() } shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
    }
})
