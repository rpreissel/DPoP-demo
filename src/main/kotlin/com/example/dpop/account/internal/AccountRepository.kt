package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    fun findByPersonId(personId: Long): Account?

    /** Ids only - unlike `findAll()`, doesn't pull every account's JSON collections into the heap. */
    @Query("select a.id from Account a")
    fun findAllIds(): List<Long>
}