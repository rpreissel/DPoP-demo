package com.example.dpop.orchestrator.session;

import com.example.dpop.orchestrator.flow.FlowSessionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class BindingFlowSessionService {

    private final BindingSessionRepository repository;

    public BindingFlowSessionService(BindingSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<BindingSession> findByBindingKeyRef(String bindingKeyRef) {
        return repository.findByBindingKeyRef(bindingKeyRef);
    }

    @Transactional
    public BindingSession getOrCreateByBindingKeyRef(String bindingKeyRef) {
        BindingSession session = findByBindingKeyRef(bindingKeyRef)
                .orElseGet(() -> BindingSession.createFlow(bindingKeyRef, defaultExpireAt()));
        session.touch();
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public BindingSession requireSession(UUID sessionId, String bindingKeyRef) {
        return findByBindingKeyRef(bindingKeyRef)
                .filter(s -> s.getSessionId().equals(sessionId))
                .orElseThrow(() -> new FlowSessionException("Invalid or unknown session"));
    }

    @Transactional
    public void deleteByBindingKeyRef(String bindingKeyRef) {
        findByBindingKeyRef(bindingKeyRef).ifPresent(repository::delete);
    }

    @Transactional
    public BindingSession createNewByBindingKeyRef(String bindingKeyRef) {
        findByBindingKeyRef(bindingKeyRef).ifPresent(repository::delete);
        BindingSession session = BindingSession.createFlow(bindingKeyRef, defaultExpireAt());
        return repository.save(session);
    }

    @Transactional
    public void touch(BindingSession session) {
        session.touch();
        repository.save(session);
    }

    @Transactional
    public BindingSession save(BindingSession session) {
        return repository.save(session);
    }

    private Instant defaultExpireAt() {
        return Instant.now().plus(30, ChronoUnit.MINUTES);
    }
}
