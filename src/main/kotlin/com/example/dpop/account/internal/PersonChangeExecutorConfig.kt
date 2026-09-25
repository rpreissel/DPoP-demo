package com.example.dpop.account.internal

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

internal const val PERSON_CHANGE_EXECUTOR = "personChangeExecutor"

/**
 * One thread for the directory's change events (review 2026-09, M-13) - same reasoning as the
 * Keycloak sync's own lane (`KeycloakSyncExecutorConfig`): order matters, parallelism buys nothing.
 */
@Configuration
class PersonChangeExecutorConfig {

    @Bean(PERSON_CHANGE_EXECUTOR)
    fun personChangeExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        setThreadNamePrefix("person-change-")
    }
}
