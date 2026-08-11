package com.example.dpop.orchestrator.registration;

import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.ClientSessionRepository;
import com.example.dpop.orchestrator.session.SessionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationSessionService {

    private final ClientSessionRepository repository;

    public RegistrationSessionService(ClientSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ClientSession> findByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprintAndType(jwkThumbprint, SessionType.REG);
    }

    @Transactional
    public UUID getOrCreateSession(String jwkThumbprint) {
        ClientSession session = repository.findByJwkThumbprintAndType(jwkThumbprint, SessionType.REG)
                .orElseGet(() -> repository.save(ClientSession.createRegistration(jwkThumbprint, defaultExpireAt())));
        session.touch();
        return session.getSessionId();
    }

    @Transactional(readOnly = true)
    public ClientSession requireSession(UUID sessionId, String jwkThumbprint) {
        return repository.findByJwkThumbprintAndType(jwkThumbprint, SessionType.REG)
                .filter(s -> s.getSessionId().equals(sessionId))
                .orElseThrow(() -> new RegistrationSessionException("Invalid or unknown registration session"));
    }

    @Transactional
    public void setPersonId(UUID sessionId, String jwkThumbprint, Long personId) {
        ClientSession session = requireSession(sessionId, jwkThumbprint);
        session.setPersonId(personId);
        session.touch();
    }

    @Transactional
    public void setAccountId(UUID sessionId, String jwkThumbprint, Long accountId) {
        ClientSession session = requireSession(sessionId, jwkThumbprint);
        session.setAccountId(accountId);
        session.touch();
    }

    private Instant defaultExpireAt() {
        return Instant.now().plus(30, ChronoUnit.MINUTES);
    }
}
