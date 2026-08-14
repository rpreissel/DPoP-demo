package com.example.dpop.orchestrator.session;

import com.example.dpop.orchestrator.flow.FlowSessionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClientFlowSessionService {

    private final ClientSessionRepository repository;

    public ClientFlowSessionService(ClientSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ClientSession> findByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprint(jwkThumbprint);
    }

    @Transactional
    public ClientSession getOrCreateByJwkThumbprint(String jwkThumbprint) {
        ClientSession session = findByJwkThumbprint(jwkThumbprint)
                .orElseGet(() -> ClientSession.createFlow(jwkThumbprint, defaultExpireAt()));
        session.touch();
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public ClientSession requireSession(UUID sessionId, String jwkThumbprint) {
        return findByJwkThumbprint(jwkThumbprint)
                .filter(s -> s.getSessionId().equals(sessionId))
                .orElseThrow(() -> new FlowSessionException("Invalid or unknown session"));
    }

    @Transactional
    public void deleteByJwkThumbprint(String jwkThumbprint) {
        findByJwkThumbprint(jwkThumbprint).ifPresent(repository::delete);
    }

    @Transactional
    public ClientSession createNewByJwkThumbprint(String jwkThumbprint) {
        findByJwkThumbprint(jwkThumbprint).ifPresent(repository::delete);
        ClientSession session = ClientSession.createFlow(jwkThumbprint, defaultExpireAt());
        return repository.save(session);
    }

    @Transactional
    public void touch(ClientSession session) {
        session.touch();
        repository.save(session);
    }

    @Transactional
    public ClientSession save(ClientSession session) {
        return repository.save(session);
    }

    private Instant defaultExpireAt() {
        return Instant.now().plus(30, ChronoUnit.MINUTES);
    }
}
