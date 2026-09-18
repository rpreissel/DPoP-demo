package com.example.dpop.auth_email.internal.enrollemail

import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface EnrollEmailToolSessionRepository : JpaRepository<EnrollEmailToolSession, UUID> {
    @Modifying
    @Query("delete from EnrollEmailToolSession d where d.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
