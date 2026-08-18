package com.example.dpop.account;

import com.example.dpop.account.internal.Account;
import com.example.dpop.account.internal.AccountIdentification;
import com.example.dpop.account.internal.AccountRepository;
import com.example.dpop.account.internal.AuthenticationMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
    public AccountProfile identifyAccount(Long personId,
                                          String identificationMethod,
                                          String identificationQuality,
                                          UUID registrationSessionId,
                                          Map<String, Object> details) {
        Account account = accountRepository.findByPersonId(personId)
                .orElseGet(() -> new Account(personId, Instant.now()));

        AccountIdentification identification = new AccountIdentification(
                identificationMethod,
                identificationQuality,
                Instant.now(),
                registrationSessionId.toString(),
                details
        );
        account.addIdentification(identification);
        return toProfile(accountRepository.save(account));
    }

    @Transactional
    public AccountProfile createAccount(Long personId,
                                        String identificationMethod,
                                        String identificationQuality,
                                        UUID registrationSessionId,
                                        Map<String, Object> details) {
        return identifyAccount(personId, identificationMethod, identificationQuality, registrationSessionId, details);
    }

    @Transactional
    public AccountProfile addAuthenticationMethod(Long accountId,
                                                  String method,
                                                  boolean active,
                                                  Map<String, Object> details) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        account.addAuthenticationMethod(new AuthenticationMethod(method, active, Instant.now(), details));
        return toProfile(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public Optional<AccountProfile> findByPersonId(Long personId) {
        return accountRepository.findByPersonId(personId).map(this::toProfile);
    }

    @Transactional(readOnly = true)
    public Optional<AccountProfile> findAccountProfile(Long accountId) {
        return accountRepository.findById(accountId).map(this::toProfile);
    }

    @Transactional(readOnly = true)
    public boolean hasActiveAuthenticationMethod(Long accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getAuthenticationMethods)
                .map(methods -> methods.stream().anyMatch(AuthenticationMethod::isActive))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<String> findActiveSmsPhoneNumber(Long accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getAuthenticationMethods)
                .stream()
                .flatMap(methods -> methods.stream()
                        .filter(AuthenticationMethod::isActive)
                        .filter(method -> "sms".equals(method.getMethod()))
                        .map(AuthenticationMethod::getDetails)
                        .map(details -> details == null ? null : details.get("phoneNumber"))
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .filter(phone -> !phone.isBlank()))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public List<String> findActiveAuthenticationMethods(Long accountId) {
        return findAccountProfile(accountId)
                .map(AccountProfile::activeAuthenticationMethods)
                .orElse(List.of());
    }

    private AccountProfile toProfile(Account account) {
        List<String> activeMethods = account.getAuthenticationMethods() == null
                ? List.of()
                : account.getAuthenticationMethods().stream()
                .filter(AuthenticationMethod::isActive)
                .map(AuthenticationMethod::getMethod)
                .distinct()
                .toList();
        return new AccountProfile(account.getId(), account.getPersonId(), activeMethods);
    }
}
