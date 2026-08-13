package com.example.dpop.orchestrator.registration;

import com.example.dpop.orchestrator.session.ClientFlowSessionService;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.RegistrationSessionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationSessionService {

    private final ClientFlowSessionService flowSessionService;

    public RegistrationSessionService(ClientFlowSessionService flowSessionService) {
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

    @Transactional
    public UUID getOrCreateSession(String jwkThumbprint) {
        ClientSession session = flowSessionService.getOrCreateByJwkThumbprint(jwkThumbprint);
        return session.getSessionId();
    }

    @Transactional(readOnly = true)
    public ClientSession requireSession(UUID sessionId, String jwkThumbprint) {
        return flowSessionService.requireSession(sessionId, jwkThumbprint);
    }

    @Transactional
    public void setPersonId(UUID sessionId, String jwkThumbprint, Long personId) {
        ClientSession session = requireSession(sessionId, jwkThumbprint);
        session.setPersonId(personId);
        flowSessionService.touch(session);
    }

    @Transactional
    public void setAccountId(UUID sessionId, String jwkThumbprint, Long accountId) {
        ClientSession session = requireSession(sessionId, jwkThumbprint);
        session.setAccountId(accountId);
        flowSessionService.touch(session);
    }
}
