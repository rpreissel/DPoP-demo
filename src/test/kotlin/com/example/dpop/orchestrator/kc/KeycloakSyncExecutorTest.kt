package com.example.dpop.orchestrator.kc

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Async
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Keycloak account sync runs one sync at a time (KeycloakSyncExecutorConfig). The real listener
 * is `keycloak`-profile only and talks to Keycloak, so this drives a probe listener carrying the
 * exact same annotation pair - and separately pins that the real listener still carries it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(KeycloakSyncExecutorTest.ProbeConfig::class)
class KeycloakSyncExecutorTest : BehaviorSpec() {

    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var probe: ProbeConfig

    init {
        given("several accounts changed in separate transactions at once") {
            then("their syncs run one after another on the kc-sync thread") {
                // Separate transactions, like three accounts committed by different requests - one
                // transaction would not show whether the listener invocations overlap.
                repeat(3) { i -> transactions.executeWithoutResult { events.publishEvent(ProbeSync(i)) } }

                eventually { probe.threads.size == 3 }
                probe.threads shouldHaveSize 3
                probe.threads.forEach { it shouldStartWith "kc-sync-" }
                probe.maxConcurrent.get() shouldBe 1
            }
        }

        given("any other @Async method") {
            then("it still runs on Spring Boot's shared pool, not on the single sync thread") {
                // Without spring.task.execution.mode=force, Boot drops its own executor as soon as
                // keycloakSyncExecutor exists, and Spring falls back to a SimpleAsyncTaskExecutor
                // (a fresh thread per task) - hence the positive check on Boot's "task-" prefix
                // rather than merely "not kc-sync-".
                probe.plainAsync().get() shouldStartWith "task-"
            }
        }

        given("the real KeycloakAccountSyncListener") {
            then("both its listeners are routed to the sync executor") {
                listOf("onAccountChanged", "onAccountDeleted").forEach { name ->
                    val method = KeycloakAccountSyncListener::class.java.methods.single { it.name == name }
                    AnnotatedElementUtils.findMergedAnnotation(method, Async::class.java)?.value shouldBe KEYCLOAK_SYNC_EXECUTOR
                }
            }
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(20)
    }

    data class ProbeSync(val index: Int)

    /** On the configuration class itself for the same proxying reason as in EventPublicationRegistryTest. */
    @TestConfiguration
    class ProbeConfig {
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxConcurrent = AtomicInteger()
        private val running = AtomicInteger()

        @ApplicationModuleListener
        @Async(KEYCLOAK_SYNC_EXECUTOR)
        fun on(event: ProbeSync) {
            val now = running.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            // Long enough that parallel invocations would overlap.
            Thread.sleep(100)
            threads += Thread.currentThread().name
            running.decrementAndGet()
        }

        @Async
        fun plainAsync(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)
    }
}
