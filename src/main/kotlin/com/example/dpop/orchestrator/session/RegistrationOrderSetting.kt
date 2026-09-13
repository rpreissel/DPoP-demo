package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The runtime feature flag for REGISTER's "Enrollment zuerst" experiment
 * (`RegisterEnrollFirstStrategy`/`RegisterDispatchStrategy`, docs/04-orchestrierung.md) - a single,
 * fixed row (`id = "default"`), same "absence means the default" idiom as `ToolAvailability`
 * (`orchestrator.tool`): no row yet means `enrollFirst = false`, the ident-first status quo.
 */
@Entity
@Table(name = "registration_order_setting")
class RegistrationOrderSetting(
    @Id
    @Column(name = "id", nullable = false, length = 50)
    var id: String = DEFAULT_ID
) {
    @Column(name = "enroll_first", nullable = false)
    var enrollFirst: Boolean = false

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }

    companion object {
        const val DEFAULT_ID = "default"
    }
}
