package com.example.dpop.id_nect.internal

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Each module cleans up its own tool-session working data by age (docs/07-betrieb.md #3). */
@Component
class IdNectRetentionJob(private val repository: IdNectToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }
}
