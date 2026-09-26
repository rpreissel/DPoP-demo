package com.example.dpop.account.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountRetractionRepository : JpaRepository<AccountRetraction, Long>
