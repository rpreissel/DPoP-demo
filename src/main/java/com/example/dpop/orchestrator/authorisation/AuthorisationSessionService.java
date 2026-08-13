package com.example.dpop.orchestrator.authorisation;

import com.example.dpop.orchestrator.session.ClientFlowSessionService;
import com.example.dpop.orchestrator.session.ClientSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthorisationSessionService {

    private final ClientFlowSessionService flowSessionService;

    public AuthorisationSessionService(ClientFlowSessionService flowSessionService) {
        this.flowSessionService = flowSessionService;
    }

    @Transactional(readOnly = true)
    public Optional<ClientSession> findByJwkThumbprint(String jwkThumbprint) {
        return flowSessionService.findByJwkThumbprint(jwkThumbprint);
    }

    @Transactional
    public void deleteByJwkThumbprint(String jwkThumbprint) {
        flowSessionService.deleteByJwkThumbprint(jwkThumbprint);
    }

    @Transactional(readOnly = true)
    public ClientSession requireSession(UUID sessionId, String jwkThumbprint) {
        return flowSessionService.requireSession(sessionId, jwkThumbprint);
    }

    @Transactional
    public ClientSession createSession(String jwkThumbprint, Long accountId) {
        flowSessionService.deleteByJwkThumbprint(jwkThumbprint);
        ClientSession session = ClientSession.createFlow(jwkThumbprint, defaultExpireAt());
        session.setAccountId(accountId);
        return flowSessionService.save(session);
    }

    private Instant defaultExpireAt() {
        return Instant.now().plus(30, ChronoUnit.MINUTES);
    }
}
