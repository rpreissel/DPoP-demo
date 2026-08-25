package com.example.dpop.auth_device.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Long-lived, account-bound device credential: the public half of a non-extractable device key
 * pair (docs/03-tool-architektur.md, enroll-device). No password-style hash to store - the
 * credential IS the public key, verified per-attempt via DeviceProofValidator against a
 * self-signed proof only the matching private key could have produced.
 */
@Entity
@Table(name = "device_enrollment")
class DeviceEnrollment(
    var kty: String? = null,
    var crv: String? = null,
    var x: String? = null,
    var y: String? = null,
    var thumbprint: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
