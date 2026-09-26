package com.example.dpop.account.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

internal const val PERSON_CHANGE_EXECUTOR = "personChangeExecutor"

/**
 * One thread for the directory's change events (review 2026-09, M-13): two changes to the same
 * person must be applied in the order the directory published them, and the Event Publication
 * Registry guarantees delivery, not order. Parallelism buys nothing here.
 *
 * Declaring any `Executor` bean makes Spring Boot back off from its own `applicationTaskExecutor`
 * - every other `@Async` in the application would silently end up on this single thread.
 * `spring.task.execution.mode: force` in application.yml keeps Boot's pool in place as the default.
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
