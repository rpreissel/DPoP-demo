package com.example.dpop.orchestrator.session;

import com.example.dpop.orchestrator.session.AttemptStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SessionManagementService {

    private final ChannelSessionRepository channelSessionRepository;
    private final ProcessSessionRepository processSessionRepository;
    private final OrchestratorAttemptRepository attemptRepository;

    public SessionManagementService(
            ChannelSessionRepository channelSessionRepository,
            ProcessSessionRepository processSessionRepository,
            OrchestratorAttemptRepository attemptRepository
    ) {
        this.channelSessionRepository = channelSessionRepository;
        this.processSessionRepository = processSessionRepository;
        this.attemptRepository = attemptRepository;
    }

    // ChannelSession management

    public ChannelSession createChannelSession(
            ChannelSession.Channel channel,
            String bindingKeyRef,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        ChannelSession session = new ChannelSession(channel, bindingKeyRef, expiresAt);
        return channelSessionRepository.save(session);
    }

    public Optional<ChannelSession> getChannelSessionByBindingKeyRef(String bindingKeyRef) {
        return channelSessionRepository.findByBindingKeyRef(bindingKeyRef)
                .filter(s -> !s.isExpired());
    }

    public ChannelSession getOrCreateChannelSession(
            String bindingKeyRef,
            ChannelSession.Channel channel,
            Duration ttl
    ) {
        return getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseGet(() -> createChannelSession(channel, bindingKeyRef, ttl));
    }

    public void updateChannelSession(ChannelSession session) {
        session.touch();
        channelSessionRepository.save(session);
    }

    // ProcessSession management

    public RegistrationProcessSession createRegistrationProcessSession(
            UUID channelSessionId,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        RegistrationProcessSession session = new RegistrationProcessSession(channelSessionId, expiresAt);
        return processSessionRepository.save(session);
    }

    public LoginProcessSession createLoginProcessSession(
            UUID channelSessionId,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        LoginProcessSession session = new LoginProcessSession(channelSessionId, expiresAt);
        return processSessionRepository.save(session);
    }

    public StepUpProcessSession createStepUpProcessSession(
            UUID channelSessionId,
            String requiredAcr,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        StepUpProcessSession session = new StepUpProcessSession(channelSessionId, requiredAcr, expiresAt);
        return processSessionRepository.save(session);
    }

    public Optional<ProcessSession> getProcessSessionById(UUID processSessionId) {
        return processSessionRepository.findById(processSessionId)
                .filter(s -> !s.isExpired());
    }

    public Optional<ProcessSession> getLatestProcessSessionByChannel(
            UUID channelSessionId,
            ProcessPurpose purpose
    ) {
        return processSessionRepository.findByChannelSessionIdAndPurpose(channelSessionId, purpose)
                .filter(s -> !s.isExpired());
    }

    public void updateProcessSession(ProcessSession session) {
        processSessionRepository.save(session);
    }

    public void consumeProcessSession(UUID processSessionId) {
        getProcessSessionById(processSessionId).ifPresent(session -> {
            session.consume();
            updateProcessSession(session);
        });
    }

    // OrchestratorAttempt management

    public IdentificationAttempt createIdentificationAttempt(
            UUID processSessionId,
            String nextContext,
            String nextStep,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        IdentificationAttempt attempt = new IdentificationAttempt(processSessionId, nextContext, nextStep, expiresAt);
        return attemptRepository.save(attempt);
    }

    public AuthenticationAttempt createAuthenticationAttempt(
            UUID processSessionId,
            String nextContext,
            String nextStep,
            Duration ttl
    ) {
        Instant expiresAt = Instant.now().plus(ttl);
        AuthenticationAttempt attempt = new AuthenticationAttempt(processSessionId, nextContext, nextStep, expiresAt);
        return attemptRepository.save(attempt);
    }

    public Optional<OrchestratorAttempt> getLatestAttemptForProcessSession(UUID processSessionId) {
        return attemptRepository.findLatestByProcessSessionId(processSessionId)
                .filter(a -> !a.isExpired());
    }

    public void updateAttempt(OrchestratorAttempt attempt) {
        attemptRepository.save(attempt);
    }

    public Optional<ChannelSession> getChannelSessionById(UUID channelSessionId) {
        return channelSessionRepository.findById(channelSessionId)
                .filter(s -> !s.isExpired());
    }

    public Optional<OrchestratorAttempt> getAttemptById(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .filter(a -> !a.isExpired());
    }

    public void completeAttempt(UUID attemptId, String nextContext, String nextStep) {
        attemptRepository.findById(attemptId).ifPresent(attempt -> {
            attempt.setStatus(AttemptStatus.VERIFIED);
            attempt.setNextContext(nextContext);
            attempt.setNextStep(nextStep);
            attemptRepository.save(attempt);
        });
    }
}
