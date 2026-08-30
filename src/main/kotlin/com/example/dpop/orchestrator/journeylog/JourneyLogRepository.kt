package com.example.dpop.orchestrator.journeylog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JourneyLogRepository : JpaRepository<JourneyLogEntry, UUID> {
    fun findByBindingKeyRefOrderByCreatedAtDesc(bindingKeyRef: String): List<JourneyLogEntry>
}
