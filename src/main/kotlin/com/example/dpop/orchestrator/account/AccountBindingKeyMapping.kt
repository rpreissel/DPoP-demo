package com.example.dpop.orchestrator.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "account_binding_key_mapping")
class AccountBindingKeyMapping(
    @Id
    @Column(name = "binding_key_ref", nullable = false, length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
)
