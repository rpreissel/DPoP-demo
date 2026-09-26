package com.example.dpop.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Failed attempts arriving at the same moment on a counter that does not exist yet (review
 * 2026-09-26, T-3): each one counts. The row is created by whichever request comes first; the others
 * must neither fail nor reset it to zero.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttemptCounterConcurrencyDbTest(
    private val attemptCounter: AttemptCounter,
    private val repository: AttemptThrottleRepository,
) : BehaviorSpec({

    given("eight failed attempts at once on a fresh subject") {
        then("all eight are counted") {
            val subject = "concurrency-" + UUID.randomUUID()
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            try {
                val results = (1..8).map {
                    pool.submit(Callable {
                        start.await()
                        runCatching { attemptCounter.recordFailure(ThrottleScope.ACCOUNT, subject, maxFailures = 100, lockout = Duration.ofMinutes(15)) }
                    })
                }
                start.countDown()
                results.map { it.get() }.filter { it.isFailure }.map { it.exceptionOrNull()?.toString() } shouldBe emptyList()
            } finally {
                pool.shutdownNow()
            }
            repository.findFailedCount(ThrottleScope.ACCOUNT, subject) shouldBe 8
        }
    }
})
