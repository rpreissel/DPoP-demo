package com.example.dpop.orchestrator.registration;

import com.example.dpop.orchestrator.dpop.DpopProof;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationSessionService {

    private final RegistrationSessionRepository repository;

    public RegistrationSessionService(RegistrationSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<RegistrationSession> findByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprint(jwkThumbprint);
    }

    @Transactional
    public UUID getOrCreateSession(DpopProof proof, String jwkThumbprint) {
        RegistrationSession session = repository.findByJwkThumbprint(jwkThumbprint)
                .orElseGet(() -> repository.save(new RegistrationSession(jwkThumbprint)));
        session.touch();
        return session.getId();
    }

    @Transactional(readOnly = true)
    public RegistrationSession requireSession(UUID sessionId, String jwkThumbprint) {
        return repository.findById(sessionId)
                .filter(s -> s.getJwkThumbprint().equals(jwkThumbprint))
                .orElseThrow(() -> new RegistrationSessionException("Invalid or unknown registration session"));
    }

    @Transactional
    public void setPersonId(UUID sessionId, String jwkThumbprint, Long personId) {
        RegistrationSession session = requireSession(sessionId, jwkThumbprint);
        session.setPersonId(personId);
        session.touch();
    }
}
