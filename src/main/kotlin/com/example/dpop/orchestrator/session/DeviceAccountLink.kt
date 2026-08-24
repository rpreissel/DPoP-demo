package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Persistent device-to-account pairing, independent of any single ChannelSession. A DPoP key
 * only proves WHICH DEVICE is talking - it must never itself be the lookup that resumes a
 * session; resuming a specific session requires the client to present a known
 * `channelSessionId` (validated against this device's key). This link is what still lets a
 * KNOWN device skip straight to LOGIN on a brand-new channel - e.g. after logout, or after the
 * app lost its remembered `channelSessionId` - instead of requiring a fresh `ident-fsc` every
 * time it reconnects.
 */
@Entity
@Table(name = "device_account_link")
class DeviceAccountLink(
    @Id
    @Column(name = "binding_key_ref", nullable = false, length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }
}
