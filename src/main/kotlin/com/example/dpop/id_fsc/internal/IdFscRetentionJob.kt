package com.example.dpop.id_fsc.internal

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Each module cleans up its own tool-session-scoped working data by age, without a signal from the
 * orchestrator (docs/07-betrieb.md #3) - id_fsc.ident_tool_session holds KVNR/name and is pointless
 * after the process has moved on.
 */
@Component
class IdFscRetentionJob(private val repository: IdentFscToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }

}
