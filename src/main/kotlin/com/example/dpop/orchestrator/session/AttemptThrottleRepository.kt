package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Deliberately offers no read-modify-write counting path: every mutation below is a SINGLE
 * statement, because a load-then-save over this table is exactly what an attacker parallelizes
 * (see [AttemptCounter]). Creating a missing counter row is not part of this interface either -
 * that needs its own transaction, see [AttemptThrottleRowInitializer].
 */
@Repository
interface AttemptThrottleRepository : JpaRepository<AttemptThrottle, AttemptThrottleId> {

    /**
     * One atomic increment plus the lockout decision derived from that SAME post-increment value.
     * The row lock this takes is held until the surrounding transaction commits, so the follow-up
     * read in [AttemptCounter] observes a value no concurrent attempt can still move.
     *
     * @return 0 when no counter row exists yet - the caller's signal to create one and retry,
     *   never a silently uncounted attempt.
     */
    @Modifying
    @Query(
        """
            update AttemptThrottle t
               set t.failedCount = t.failedCount + 1,
                   t.lockedUntil = case when t.failedCount + 1 >= :maxFailures then :lockUntil else t.lockedUntil end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
        """
    )
    fun incrementFailure(
        @Param("scope") scope: ThrottleScope,
        @Param("subject") subject: String,
        @Param("maxFailures") maxFailures: Int,
        @Param("lockUntil") lockUntil: Instant,
        @Param("now") now: Instant
    ): Int

    /**
     * Rolling-window counterpart of [incrementFailure]: the window restart happens inside the same
     * statement as the increment, so two requests arriving either side of the window boundary can
     * never both conclude "mine starts the new window" and both reset the counter to 1.
     */
    @Modifying
    @Query(
        """
            update AttemptThrottle t
               set t.failedCount = case when t.updatedAt < :windowStart then 1 else t.failedCount + 1 end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
        """
    )
    fun incrementWithinWindow(
        @Param("scope") scope: ThrottleScope,
        @Param("subject") subject: String,
        @Param("windowStart") windowStart: Instant,
        @Param("now") now: Instant
    ): Int

    @Modifying
    @Query(
        """
            update AttemptThrottle t
               set t.failedCount = 0,
                   t.lockedUntil = null,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
               and (t.failedCount <> 0 or t.lockedUntil is not null)
        """
    )
    fun resetCounter(
        @Param("scope") scope: ThrottleScope,
        @Param("subject") subject: String,
        @Param("now") now: Instant
    ): Int

    @Query("select t.failedCount from AttemptThrottle t where t.id.scope = :scope and t.id.subject = :subject")
    fun findFailedCount(@Param("scope") scope: ThrottleScope, @Param("subject") subject: String): Int?

    @Query("select t.lockedUntil from AttemptThrottle t where t.id.scope = :scope and t.id.subject = :subject")
    fun findLockedUntil(@Param("scope") scope: ThrottleScope, @Param("subject") subject: String): Instant?
}
