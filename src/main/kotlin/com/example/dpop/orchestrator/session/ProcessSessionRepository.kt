package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.List
import java.util.Optional
import java.util.UUID

@Repository
interface ProcessSessionRepository : JpaRepository<ProcessSession, UUID> {
    fun findByChannelSessionIdAndPurpose(channelSessionId: UUID, purpose: ProcessPurpose): Optional<ProcessSession>

    fun findByChannelSessionIdOrderByCreatedAtDesc(channelSessionId: UUID): List<ProcessSession>
}
