package com.example.dpop.orchestrator.kc

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Bean name [KeycloakAccountSyncListener] hands its `@Async` work to. */
const val KEYCLOAK_SYNC_EXECUTOR = "keycloakSyncExecutor"

/**
 * One thread for every Keycloak account sync - they run strictly one after another, across all
 * accounts, instead of side by side on Spring's shared async pool.
 *
 * `@ApplicationModuleListener` is `@Async` without a qualifier: every event becomes its own task on
 * the shared pool, so three accounts changed in one go meant three concurrent Admin-API calls. The
 * Event Publication Registry does not change that - it guarantees delivery, not order or
 * exclusivity. Nothing about this mirror benefits from parallelism (a handful of calls, each
 * reading the account's current state), while parallel calls kept finding new ways to collide:
 * first two syncs of the same account (409, doubled keypair), then the first user creations of a
 * fresh realm racing inside Keycloak itself. One lane removes the whole class, not just the
 * collision seen last.
 *
 * No `@Profile`: the listener is `keycloak`-only, but this is one idle, lazily started thread, and
 * leaving it unconditional lets tests import it without that profile.
 *
 * Declaring any `Executor` bean makes Spring Boot back off from its own `applicationTaskExecutor`
 * - every other `@Async` in the application would silently end up on this single thread.
 * `spring.task.execution.mode: force` in application.yml keeps Boot's pool in place as the default.
 */
@Configuration(proxyBeanMethods = false)
class KeycloakSyncExecutorConfig {

    @Bean(KEYCLOAK_SYNC_EXECUTOR)
    fun keycloakSyncExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        setThreadNamePrefix("kc-sync-")
    }
}
