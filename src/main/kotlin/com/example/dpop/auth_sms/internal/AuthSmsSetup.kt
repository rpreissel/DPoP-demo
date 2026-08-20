package com.example.dpop.auth_sms.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "auth_sms")
class AuthSmsSetup(
    var phoneNumber: String? = null,
    var tan: String? = null,
    var validated: Boolean = false,
    var createdAt: Instant? = null,
    var updatedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
