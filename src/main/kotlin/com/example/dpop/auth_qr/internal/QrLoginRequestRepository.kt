package com.example.dpop.auth_qr.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface QrLoginRequestRepository : JpaRepository<QrLoginRequest, String> {

    /**
     * The app's approval - atomic and conditional like every transition here (docs/07-betrieb.md
     * #5): a plain read-then-write would let two concurrent decisions both believe they won. Only a
     * still-PENDING, unexpired request is approved; [newExpiresAt] gives the browser a fresh window
     * to type the code. Returns the rows changed: `0` means no longer PENDING, or expired.
     */
    @Modifying
    @Query(
        "update QrLoginRequest q set q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.APPROVED, " +
            "q.resolvingAccountId = :accountId, q.confirmationCodeHash = :codeHash, q.expiresAt = :newExpiresAt " +
            "where q.pairingCode = :pairingCode and q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.PENDING " +
            "and q.expiresAt > :now"
    )
    fun approveIfPending(
        @Param("pairingCode") pairingCode: String,
        @Param("accountId") accountId: Long,
        @Param("codeHash") codeHash: String,
        @Param("now") now: Instant,
        @Param("newExpiresAt") newExpiresAt: Instant
    ): Int

    /** The app's refusal; same conditions as [approveIfPending]. */
    @Modifying
    @Query(
        "update QrLoginRequest q set q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.DENIED " +
            "where q.pairingCode = :pairingCode and q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.PENDING " +
            "and q.expiresAt > :now"
    )
    fun denyIfPending(@Param("pairingCode") pairingCode: String, @Param("now") now: Instant): Int

    /**
     * The browser typed the right code in time: APPROVED -> COMPLETED, once. `0` means wrong code,
     * expired, burned or already completed - the caller then counts the attempt.
     */
    @Modifying
    @Query(
        "update QrLoginRequest q set q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.COMPLETED " +
            "where q.pairingCode = :pairingCode and q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.APPROVED " +
            "and q.confirmationCodeHash = :codeHash and q.expiresAt > :now"
    )
    fun completeIfConfirmed(
        @Param("pairingCode") pairingCode: String,
        @Param("codeHash") codeHash: String,
        @Param("now") now: Instant
    ): Int

    /** One wrong code: counted atomically, and at [maxAttempts] the request is burned (EXPIRED). */
    @Modifying
    @Query(
        "update QrLoginRequest q set q.confirmationAttempts = q.confirmationAttempts + 1, " +
            "q.status = case when q.confirmationAttempts + 1 >= :maxAttempts " +
            "then com.example.dpop.auth_qr.internal.QrLoginStatus.EXPIRED else q.status end " +
            "where q.pairingCode = :pairingCode and q.status = com.example.dpop.auth_qr.internal.QrLoginStatus.APPROVED"
    )
    fun countWrongConfirmation(@Param("pairingCode") pairingCode: String, @Param("maxAttempts") maxAttempts: Int): Int

    @Modifying
    @Query("delete from QrLoginRequest q where q.expiresAt < :cutoff")
    fun deleteByExpiresAtBefore(@Param("cutoff") cutoff: Instant): Int
}
