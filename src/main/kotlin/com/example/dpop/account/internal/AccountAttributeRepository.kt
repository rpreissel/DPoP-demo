package com.example.dpop.account.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** Append-only; Phase 1 never reads this back, only AccountService writes new claims to it. */
@Repository
interface AccountAttributeRepository : JpaRepository<AccountAttribute, Long>
