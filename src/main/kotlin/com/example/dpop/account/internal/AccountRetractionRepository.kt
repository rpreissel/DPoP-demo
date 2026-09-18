package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountRetractionRepository : JpaRepository<AccountRetraction, Long>
