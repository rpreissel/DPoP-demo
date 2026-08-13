package com.example.dpop.orchestrator.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AccountJwkMappingService {

    private final AccountJwkMappingRepository repository;

    public AccountJwkMappingService(AccountJwkMappingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void mapJwkToAccount(String jwkThumbprint, Long accountId) {
        repository.findByJwkThumbprint(jwkThumbprint).ifPresent(repository::delete);
        repository.save(new AccountJwkMapping(jwkThumbprint, accountId, Instant.now()));
    }

    @Transactional(readOnly = true)
    public Optional<Long> findAccountIdByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprint(jwkThumbprint).map(AccountJwkMapping::getAccountId);
    }
}
