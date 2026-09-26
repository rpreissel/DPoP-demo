package com.example.dpop.orchestrator.tool

import com.example.dpop.orchestrator.domain.ChannelType
import com.example.dpop.orchestrator.session.ChannelSession
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The backend half of tool availability (the other half is
 * [com.example.dpop.orchestrator.session.ChannelSession.availableClientTools]), per channel type:
 * the operator can switch a tool off for the App or the Web channel alone, and decides the order
 * each channel offers its tools in. Read live, never cached: a change must take effect on the very
 * next step of an already-running journey, not just for freshly created channels
 * (docs/03-tool-architektur.md, availability).
 */
@Service
@Transactional
class ToolAvailabilityService(
    private val repository: ToolAvailabilityRepository,
    private val toolRegistry: ToolHandlerRegistry,
    private val defaults: ToolDefaults,
    @Value("\${demo.mode}") private val demoMode: Boolean
) {
    /**
     * Tools that declare themselves demo-only ([com.example.dpop.tool_spi.ToolDescriptor.demoOnly])
     * are off outside `demo.mode` - decided here, next to the operator's switches but not among
     * them, so no setting can turn one back on (review 2026-09, M-1).
     */
    private val demoOnlyToolIds: Set<String> =
        if (demoMode) emptySet() else toolRegistry.descriptors().filter { it.demoOnly != null }.mapTo(mutableSetOf()) { it.toolId.value }

    fun isEnabled(toolId: String, channel: ChannelType): Boolean =
        toolId !in demoOnlyToolIds && (repository.findByIdOrNull(ToolAvailabilityKey(toolId, channel))?.enabled ?: true)

    fun disabledToolIds(channel: ChannelType): Set<String> =
        repository.findByChannel(channel).filterNot { it.enabled }.mapTo(mutableSetOf()) { it.toolId!! } + demoOnlyToolIds

    /** Every tool that is switched off in any channel - channel, toolId, reason. */
    fun disabledEntries(): List<ToolAvailability> = repository.findAll().filterNot { it.enabled }

    fun disable(toolId: String, channel: ChannelType, reason: String?) =
        update(toolId, channel) { it.enabled = false; it.reason = reason }

    fun enable(toolId: String, channel: ChannelType) =
        update(toolId, channel) { it.enabled = true; it.reason = null }

    fun hasAnySetting(): Boolean = repository.count() > 0

    /** Replaces every setting with the demo's preset ([ToolDefaults]): order and locks per channel. */
    fun applyDefaults() {
        repository.deleteAll()
        repository.flush()
        defaults.channels.forEach { (channel, preset) ->
            setOrder(channel, preset.order)
            preset.disabled.forEach { disable(it, channel, PRESET_REASON) }
        }
    }

    /**
     * Sets [channel]'s ranking to exactly [toolIds] (first = offered first); every tool not in the
     * list loses its rank and moves behind the ranked ones.
     */
    fun setOrder(channel: ChannelType, toolIds: List<String>) {
        repository.findByChannel(channel).filter { it.position != null && it.toolId !in toolIds }
            .forEach { entry -> update(entry.toolId!!, channel) { it.position = null } }
        toolIds.forEachIndexed { index, toolId -> update(toolId, channel) { it.position = index } }
    }

    /** toolId -> rank for [channel]; unranked tools are absent. */
    fun rankOf(channel: ChannelType): Map<String, Int> =
        repository.findByChannel(channel).mapNotNull { e -> e.position?.let { e.toolId!! to it } }.toMap()

    /**
     * [tools] in [channel]'s order: ranked ones first by rank, the rest after them in the default
     * order - by role (each role is one kind of selection list), then method. The same default the
     * admin page shows, so what it lists is what a user is offered.
     */
    fun ordered(channel: ChannelType, tools: Collection<ToolId>): List<ToolId> {
        val rank = rankOf(channel)
        return tools.sortedWith(compareBy<ToolId> { rank[it.value] ?: Int.MAX_VALUE }.then(defaultOrder))
    }

    private val defaultOrder: Comparator<ToolId> = compareBy(
        { ROLE_ORDER.indexOf(toolRegistry.descriptorOf(it).role) },
        { toolRegistry.descriptorOf(it).method },
        { it.value }
    )

    companion object {
        const val PRESET_REASON = "Voreinstellung der Demo"

        /**
         * Each role is one kind of selection list, so ranking only means something within a role.
         * Listed in the order a user meets them: identify, enroll, log in, log in by lookup - then
         * the side steps.
         */
        val ROLE_ORDER = listOf(
            MethodRole.IDENTIFICATION, MethodRole.CORRELATION, MethodRole.ENROLLMENT,
            MethodRole.IDENTIFIED_AUTH, MethodRole.LOOKUP_AUTH, MethodRole.ATTESTATION, MethodRole.PEER_APPROVAL
        )
    }

    private fun update(toolId: String, channel: ChannelType, change: (ToolAvailability) -> Unit) {
        val entry = repository.findByIdOrNull(ToolAvailabilityKey(toolId, channel))
            ?: ToolAvailability(toolId = toolId, channel = channel)
        change(entry)
        entry.updatedAt = Instant.now()
        repository.save(entry)
    }
}
