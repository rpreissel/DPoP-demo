package com.example.dpop.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceAccountLinkRepository : JpaRepository<DeviceAccountLink, String> {
    fun deleteByAccountId(accountId: Long)
}
