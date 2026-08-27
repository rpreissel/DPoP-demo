package com.example.dpop.orchestrator.tool

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A global, runtime-changeable kill-switch for one toolId (docs/03-tool-architektur.md,
 * availability). Absence of a row means enabled - only tools that were ever explicitly disabled
 * live here, so the catalog never needs pre-seeding.
 */
@Entity
@Table(name = "tool_availability")
class ToolAvailability(
    @Id
    @Column(name = "tool_id", nullable = false, length = 50)
    var toolId: String? = null
) {
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    @Column(name = "reason", length = 255)
    var reason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }
}
