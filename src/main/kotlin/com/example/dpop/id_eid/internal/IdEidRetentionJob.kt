package com.example.dpop.id_eid.internal

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Each module cleans up its own attempt-scoped data by age, without a signal from the
 * orchestrator (docs/07-betrieb.md #3) - id_eid_tool_data holds KVNR/name/PIN and is pointless
 * after the process has moved on.
 */
@Component
class IdEidRetentionJob(private val repository: IdEidToolDataRepository) {

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        repository.deleteByCreatedAtBefore(Instant.now().minus(RETENTION))
    }

    companion object {
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
