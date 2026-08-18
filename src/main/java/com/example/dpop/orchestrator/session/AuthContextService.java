package com.example.dpop.orchestrator.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AuthContextService {

    private final AuthContextRepository authContextRepository;

    public AuthContextService(AuthContextRepository authContextRepository) {
        this.authContextRepository = authContextRepository;
    }

    public AuthContext createAuthContext(Long accountId, String keycloakSessionId, String keycloakSubject) {
        AuthContext authContext = new AuthContext(accountId, keycloakSessionId, keycloakSubject);
        return authContextRepository.save(authContext);
    }

    public AuthContext updateAcr(UUID authContextId, String acr, String amr) {
        AuthContext authContext = authContextRepository.findById(authContextId)
                .orElseThrow(() -> new IllegalArgumentException("AuthContext not found"));
        
        authContext.setCurrentAcr(acr);
        authContext.setCurrentAmr(amr);
        authContext.setUpdatedAt(Instant.now());
        
        return authContextRepository.save(authContext);
    }

    public Optional<AuthContext> getAuthContext(UUID authContextId) {
        return authContextRepository.findById(authContextId);
    }
}
