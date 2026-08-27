package com.example.dpop.orchestrator.tool

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The backend half of tool availability (the other half is [com.example.dpop.orchestrator.session.ChannelSession.availableClientTools]).
 * Read live, never cached: a disable must take effect on the very next step of an already-running
 * journey, not just for freshly created channels (docs/03-tool-architektur.md, availability).
 */
@Service
@Transactional
class ToolAvailabilityService(private val repository: ToolAvailabilityRepository) {

    fun isEnabled(toolId: String): Boolean = repository.findByIdOrNull(toolId)?.enabled ?: true

    fun disabledToolIds(): Set<String> = disabledEntries().keys

    /** toolId -> reason, for every currently disabled tool. */
    fun disabledEntries(): Map<String, String?> =
        repository.findAll().filterNot { it.enabled }.associate { it.toolId!! to it.reason }

    fun disable(toolId: String, reason: String?) = upsert(toolId, enabled = false, reason)

    fun enable(toolId: String) = upsert(toolId, enabled = true, reason = null)

    private fun upsert(toolId: String, enabled: Boolean, reason: String?) {
        val entry = repository.findByIdOrNull(toolId) ?: ToolAvailability(toolId = toolId)
        entry.enabled = enabled
        entry.reason = reason
        entry.updatedAt = Instant.now()
        repository.save(entry)
    }
}
