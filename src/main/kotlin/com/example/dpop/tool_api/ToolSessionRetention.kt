package com.example.dpop.tool_api

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * How long a module's tool-session working data may live (docs/07-betrieb.md #3).
 *
 * Previously this was `private val RETENTION = Duration.ofHours(24)`, copied into ten separate
 * retention jobs. That is a data-protection statement with no place it is actually stated: nobody
 * could read the retention period without reading ten files, nobody could change it without
 * changing ten, and nothing noticed when a new module's table got no job at all.
 *
 * Configurable because the period is an operational decision, not a code one - a deployment with
 * different rules sets `tool-session.retention` instead of patching modules.
 */
@ConfigurationProperties(prefix = "tool-session")
data class ToolSessionRetentionProperties(
    /** Anything older than this, in every module's tool-session tables, is deleted. */
    val retention: Duration = Duration.ofHours(24),
    /** How often the sweep runs. Far shorter than [retention] - a missed run must not leak data past it. */
    val sweepInterval: Duration = Duration.ofHours(1)
)

/**
 * One module's own deletion of its own tool-session tables, triggered by [ToolSessionRetentionDriver].
 *
 * The module still owns WHAT it deletes - only the schedule and the cutoff are shared. That split
 * is the point: a module knows which of its tables are session-scoped and which belong to the
 * account (an `enrollment` row outlives the session that created it and must survive the sweep);
 * the orchestrator cannot know that and must not decide it. What the orchestrator can say, and
 * should say exactly once, is how long "session-scoped" means.
 *
 * Implementations are `@Transactional` in their own right, so one module's failure rolls back only
 * its own deletions.
 */
fun interface ToolSessionSweeper {
    /** Delete this module's tool-session rows created before [cutoff]. */
    fun sweep(cutoff: Instant)
}

/**
 * The single schedule behind every module's sweep. Replaces ten identical `@Scheduled` methods that
 * all fired at the same fixed delay anyway.
 *
 * Each sweeper is called in its own try/catch: a module whose sweep fails (a locked table, a
 * migration mid-flight) must not stop the other nine from running - the whole point of the sweep is
 * that data does not outlive its retention because something unrelated broke.
 */
@Component
class ToolSessionRetentionDriver(
    private val sweepers: List<ToolSessionSweeper>,
    private val properties: ToolSessionRetentionProperties
) {
    private val log = LoggerFactory.getLogger(ToolSessionRetentionDriver::class.java)

    @Scheduled(
        fixedDelayString = "\${tool-session.sweep-interval:PT1H}",
        initialDelayString = "\${tool-session.initial-sweep-delay:PT1M}"
    )
    fun sweep() {
        val cutoff = Instant.now().minus(properties.retention)
        sweepers.forEach { sweeper ->
            runCatching { sweeper.sweep(cutoff) }
                .onFailure { log.error("Tool-session sweep failed for {}", sweeper.javaClass.name, it) }
        }
    }
}
