package com.example.dpop.orchestrator.account

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AccountBindingKeyMappingService(private val repository: AccountBindingKeyMappingRepository) {

    @Transactional
    fun mapBindingKeyToAccount(bindingKeyRef: String, accountId: Long) {
        repository.findByBindingKeyRef(bindingKeyRef)?.let(repository::delete)
        repository.save(AccountBindingKeyMapping(bindingKeyRef, accountId, Instant.now()))
    }

    @Transactional(readOnly = true)
    fun findAccountIdByBindingKeyRef(bindingKeyRef: String): Long? =
        repository.findByBindingKeyRef(bindingKeyRef)?.accountId
}
