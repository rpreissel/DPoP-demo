package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One runtime feature flag, keyed by its name from
 * [com.example.dpop.orchestrator.journey.FeatureFlags] - a switch an operator can flip without a
 * redeploy. Absence of a row means off: only a flag somebody actually touched lives here, so a new
 * flag needs no seeding and its documented default keeps working until then (same idiom as
 * [com.example.dpop.orchestrator.tool.ToolAvailability]).
 *
 * Deliberately one generic table rather than a column (or a table) per experiment: a flag is
 * operational state with no schema of its own, and the alternative grew a `registration_order_
 * setting` table with an `enroll_first` column for exactly one flag. What a flag MEANS is not
 * stored here - that is the `FeatureFlags` constant and the strategy that reads it.
 */
@Entity
@Table(schema = "orchestrator", name = "feature_flag")
class FeatureFlag(
    @Id
    @Column(name = "flag_key", nullable = false, length = 100)
    var flagKey: String? = null
) {
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false

    /** Why it was flipped - operator context for the next reader, never evaluated. */
    @Column(name = "reason", length = 255)
    var reason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }
}
