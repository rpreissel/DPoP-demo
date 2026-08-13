package com.example.dpop.orchestrator.session;

import com.example.dpop.account.Account;

import java.util.List;

public interface AuthenticationMethodProvider {

    List<String> availableMethods();

    default List<String> activeMethods(Account account) {
        if (account == null || account.getAuthenticationMethods() == null) {
            return List.of();
        }
        return account.getAuthenticationMethods().stream()
                .filter(method -> method.isActive())
                .map(method -> method.getMethod())
                .distinct()
                .toList();
    }
}
