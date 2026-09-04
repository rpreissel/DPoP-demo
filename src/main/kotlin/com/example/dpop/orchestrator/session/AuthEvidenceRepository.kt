package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuthEvidenceRepository : JpaRepository<AuthEvidence, UUID> {
    fun findByAccountId(accountId: Long): List<AuthEvidence>
}
