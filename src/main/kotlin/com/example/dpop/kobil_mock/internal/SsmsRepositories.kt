package com.example.dpop.kobil_mock.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface SsmsUserRepository : JpaRepository<SsmsUser, String>

@Repository
interface SsmsAssertionRepository : JpaRepository<SsmsAssertion, String> {

    @Modifying
    @Query("delete from SsmsAssertion a where a.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: String): Int
}
