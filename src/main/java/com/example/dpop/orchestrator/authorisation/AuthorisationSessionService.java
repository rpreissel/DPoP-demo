package com.example.dpop.orchestrator.authorisation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthorisationSessionService {

    private final AuthorisationSessionRepository repository;

    public AuthorisationSessionService(AuthorisationSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<AuthorisationSession> findByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprint(jwkThumbprint);
    }
}
