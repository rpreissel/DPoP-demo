package com.example.dpop.auth_qr.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface QrLoginRequestRepository : JpaRepository<QrLoginRequest, String> {

    /**
     * The atomic, conditional transition every accept/reject goes through
     * (docs/07-betrieb.md #5) - a plain read-then-write would let
     * two concurrent decisions both believe they won. Returns the number of rows changed: `0`
     * means the request was no longer `PENDING` by the time this ran.
     */
    @Modifying
    @Query(
        "update QrLoginRequest q set q.status = :status, q.resolvingAccountId = :accountId " +
            "where q.pairingCode = :pairingCode and q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.PENDING"
    )
    fun resolveIfPending(
        @Param("pairingCode") pairingCode: String,
        @Param("status") status: QrLoginStatus,
        @Param("accountId") accountId: Long?
    ): Int
}
