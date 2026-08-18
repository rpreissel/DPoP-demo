package com.example.dpop.account;

import java.util.List;

public record AccountProfile(
        Long accountId,
        Long personId,
        List<String> activeAuthenticationMethods
) {
}
