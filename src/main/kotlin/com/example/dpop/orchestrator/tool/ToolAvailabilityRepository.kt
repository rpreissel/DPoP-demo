package com.example.dpop.orchestrator.tool

import com.example.dpop.orchestrator.domain.ChannelType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ToolAvailabilityRepository : JpaRepository<ToolAvailability, ToolAvailabilityKey> {
    fun findByChannel(channel: ChannelType): List<ToolAvailability>
}
