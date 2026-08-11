package com.example.dpop.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public String manageAccount() {
        return "account: account managed";
    }

    @Transactional
    public Account createAccount(Long personId,
                                 String identificationMethod,
                                 String identificationQuality,
                                 UUID registrationSessionId,
                                 Map<String, Object> details) {
        Account account = new Account(personId, Instant.now());
        AccountIdentification identification = new AccountIdentification(
                identificationMethod,
                identificationQuality,
                Instant.now(),
                registrationSessionId.toString(),
                details
        );
        account.addIdentification(identification);
        return accountRepository.save(account);
    }

    @Transactional
    public Account addAuthenticationMethod(Long accountId,
                                           String method,
                                           boolean active,
                                           Map<String, Object> details) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        account.addAuthenticationMethod(new AuthenticationMethod(method, active, Instant.now(), details));
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findByPersonId(Long personId) {
        return accountRepository.findByPersonId(personId);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(Long accountId) {
        return accountRepository.findById(accountId);
    }
}
