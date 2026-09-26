package com.example.dpop.id_kvnr.internal

import com.example.dpop.tool_api.ToolSessionSweeper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Self-cleanup by age (docs/07-betrieb.md #3) of `id_kvnr.ident_tool_session`, which holds the
 * submitted KVNR while the correlation step runs.
 *
 * This module had the delete query but no caller: every other module's retention was its own
 * `@Scheduled` job, so a missing one was a missing file rather than a missing implementation of
 * anything - nothing could notice. `ToolSessionCoverageTest` now does.
 */
@Component
class IdKvnrRetentionJob(private val repository: IdentKvnrToolSessionRepository) : ToolSessionSweeper {

    @Transactional
    override fun sweep(cutoff: Instant) {
        repository.deleteByCreatedAtBefore(cutoff)
    }
}
