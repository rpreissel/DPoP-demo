package com.example.dpop.orchestrator.dpop

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * One already-seen proof, keyed by `thumbprint:jti`. The PRIMARY KEY is the replay check: a
 * second insert of the same pair fails, and that failure is the detection - no read-then-write
 * window, and no in-process map that a restart empties or a second replica never sees.
 */
@Entity
@Table(name = "dpop_proof_replay")
class DpopProofReplay(
    @Id
    @Column(name = "proof_key", nullable = false, length = 255)
    var proofKey: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
)

interface DpopProofReplayRepository : JpaRepository<DpopProofReplay, String> {
    @Modifying
    @Query("delete from DpopProofReplay p where p.expiresAt < :cutoff")
    fun deleteExpired(@Param("cutoff") cutoff: Instant): Int
}
