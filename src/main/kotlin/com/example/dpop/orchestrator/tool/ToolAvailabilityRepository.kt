package com.example.dpop.orchestrator.tool

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ToolAvailabilityRepository : JpaRepository<ToolAvailability, String>
