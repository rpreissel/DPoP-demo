package com.example.dpop.account.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AccountAuthMethodRepository : JpaRepository<AccountAuthMethod, UUID> {
    fun findByAccountIdOrderByCreatedAt(accountId: Long): List<AccountAuthMethod>
    fun findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId: Long, method: String): List<AccountAuthMethod>
    fun findByIdAndAccountId(id: UUID, accountId: Long): AccountAuthMethod?

    fun existsByEnrollmentTypeAndEnrollmentIdAndAccountIdNot(enrollmentType: String, enrollmentId: String, accountId: Long): Boolean
}
