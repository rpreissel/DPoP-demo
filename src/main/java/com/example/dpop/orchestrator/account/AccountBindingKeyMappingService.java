package com.example.dpop.orchestrator.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AccountBindingKeyMappingService {

    private final AccountBindingKeyMappingRepository repository;

    public AccountBindingKeyMappingService(AccountBindingKeyMappingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void mapBindingKeyToAccount(String bindingKeyRef, Long accountId) {
        repository.findByBindingKeyRef(bindingKeyRef).ifPresent(repository::delete);
        repository.save(new AccountBindingKeyMapping(bindingKeyRef, accountId, Instant.now()));
    }

    @Transactional(readOnly = true)
    public Optional<Long> findAccountIdByBindingKeyRef(String bindingKeyRef) {
        return repository.findByBindingKeyRef(bindingKeyRef).map(AccountBindingKeyMapping::getAccountId);
    }
}
