package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountIdentificationRepository : JpaRepository<AccountIdentification, Long> {
    /** Every identification run ever recorded for this account, oldest first - read by `AccountService.absorbProvisionalAccount`, which replays them onto the absorbing account so the audit trail survives the account that carried it (ADR-20). */
    fun findByAccountIdOrderByIdentifiedAt(accountId: Long): List<AccountIdentification>
}
