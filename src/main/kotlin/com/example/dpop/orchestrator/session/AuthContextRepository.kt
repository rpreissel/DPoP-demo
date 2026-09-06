package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuthContextRepository : JpaRepository<AuthContext, UUID> {
    fun findByAccountId(accountId: Long): List<AuthContext>

    /** Every [AuthContext] currently minted from this evidence trail - [AuthEvidenceService] uses this to invalidate their cached AccessToken the moment a step-up changes what that evidence actually proves. */
    fun findByAuthEvidenceId(authEvidenceId: UUID): List<AuthContext>
}
