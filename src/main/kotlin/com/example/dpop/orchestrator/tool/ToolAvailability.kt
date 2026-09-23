package com.example.dpop.orchestrator.tool

import com.example.dpop.orchestrator.kernel.ChannelType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/** Composite key of [ToolAvailability]: one row per tool and channel type. */
data class ToolAvailabilityKey(
    var toolId: String? = null,
    var channel: ChannelType? = null
) : Serializable

/**
 * The operator's settings for one toolId in one channel type (docs/03-tool-architektur.md,
 * availability): a runtime kill-switch and the tool's rank in that channel's selection lists.
 * Absence of a row means enabled and unranked - only tools that were ever explicitly touched live
 * here, so the catalog never needs pre-seeding.
 */
@Entity
@Table(schema = "orchestrator", name = "tool_availability")
@IdClass(ToolAvailabilityKey::class)
class ToolAvailability(
    @Id
    @Column(name = "tool_id", nullable = false, length = 50)
    var toolId: String? = null,

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    var channel: ChannelType? = null
) {
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    @Column(name = "reason", length = 255)
    var reason: String? = null

    /** 0-based rank in this channel's selection lists; null = after every ranked tool. */
    @Column(name = "position")
    var position: Int? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }
}
