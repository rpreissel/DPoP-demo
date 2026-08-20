package com.example.dpop.orchestrator.account

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountBindingKeyMappingRepository : JpaRepository<AccountBindingKeyMapping, String> {

    fun findByBindingKeyRef(bindingKeyRef: String): AccountBindingKeyMapping?
}
