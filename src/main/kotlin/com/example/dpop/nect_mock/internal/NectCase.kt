package com.example.dpop.nect_mock.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class NectCaseStatus { OPEN, COMPLETED, FAILED, CANCELLED }

@Entity
@Table(schema = "nect_mock", name = "ident_case")
class NectCase(
    @Id
    var id: UUID? = null,

    @Column(name = "callback_uri", nullable = false, length = 500)
    var callbackUri: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: NectCaseStatus = NectCaseStatus.OPEN,

    /** eid / epass / eudi - which document the user chose on the jump page. */
    @Column(name = "procedure", length = 32)
    var procedure: String? = null,

    /** The attested attributes as JSON - what the relying party receives once, on redeem. */
    @Column(name = "result", length = 4000)
    var result: String? = null,

    @Column(name = "reason", length = 255)
    var reason: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "redeemed_at")
    var redeemedAt: Instant? = null
)
