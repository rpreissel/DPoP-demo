package com.example.dpop.id_eid.internal

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Each module cleans up its own tool-session-scoped working data by age, without a signal from the
 * orchestrator (docs/07-betrieb.md #3) - id_eid.ident_tool_session holds KVNR/name/PIN and is pointless
 * after the process has moved on.
 */
@Component
class IdEidRetentionJob(private val repository: IdentEidToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }

}
