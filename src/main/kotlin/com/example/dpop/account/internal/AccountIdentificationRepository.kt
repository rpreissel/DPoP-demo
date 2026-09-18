package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountIdentificationRepository : JpaRepository<AccountIdentification, Long>
