package com.example.dpop.orchestrator.flow;

import com.example.dpop.account.AccountService;
import com.example.dpop.orchestrator.account.AccountJwkMappingService;
import com.example.dpop.orchestrator.flow.handler.FscIdentificationHandler;
import com.example.dpop.orchestrator.flow.handler.SmsAuthenticationHandler;
import com.example.dpop.orchestrator.session.AuthenticationMethodProvider;
import com.example.dpop.orchestrator.session.ClientFlowSessionService;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.IdentificationMethodProvider;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FlowActionService {

    private final ClientFlowSessionService flowSessionService;
    private final AccountService accountService;
    private final AccountJwkMappingService accountJwkMappingService;
    private final IdentificationMethodProvider identificationMethodProvider;
    private final AuthenticationMethodProvider authenticationMethodProvider;
    private final Map<String, IdentificationMethodHandler> identificationHandlers;
    private final Map<String, AuthenticationMethodHandler> authenticationHandlers;

    public FlowActionService(ClientFlowSessionService flowSessionService,
                             AccountService accountService,
                             AccountJwkMappingService accountJwkMappingService,
                             IdentificationMethodProvider identificationMethodProvider,
                             AuthenticationMethodProvider authenticationMethodProvider,
                             List<IdentificationMethodHandler> identificationHandlers,
                             List<AuthenticationMethodHandler> authenticationHandlers) {
        this.flowSessionService = flowSessionService;
        this.accountService = accountService;
        this.accountJwkMappingService = accountJwkMappingService;
        this.identificationMethodProvider = identificationMethodProvider;
        this.authenticationMethodProvider = authenticationMethodProvider;
        this.identificationHandlers = identificationHandlers.stream()
                .collect(Collectors.toMap(IdentificationMethodHandler::method, Function.identity()));
        this.authenticationHandlers = authenticationHandlers.stream()
                .collect(Collectors.toMap(AuthenticationMethodHandler::method, Function.identity()));
    }

    @Transactional
    public FlowSetupResponse createFlow(String thumbprint) {
        ClientSession session = resolveOrRotateSession(thumbprint);
        return new FlowSetupResponse(session.getSessionId(), resolveInitialNextStep(session));
    }

    private ClientSession resolveOrRotateSession(String thumbprint) {
        Optional<Long> knownAccountId = accountJwkMappingService.findAccountIdByJwkThumbprint(thumbprint);
        Optional<ClientSession> existingSession = flowSessionService.findByJwkThumbprint(thumbprint);

        if (existingSession.isPresent()) {
            ClientSession session = existingSession.get();
            Long accountId = session.getAccountId();
            boolean accountHasActiveMethod = accountId != null
                    && accountService.hasActiveAuthenticationMethod(accountId);

            if (accountHasActiveMethod || knownAccountId.filter(accountService::hasActiveAuthenticationMethod).isPresent()) {
                session = flowSessionService.rotateSessionId(thumbprint);
                Long effectiveAccountId = accountId != null ? accountId : knownAccountId.orElseThrow();
                session.setAccountId(effectiveAccountId);
                return flowSessionService.save(session);
            }

            return session;
        }

        if (knownAccountId.isPresent() && accountService.hasActiveAuthenticationMethod(knownAccountId.get())) {
            ClientSession session = flowSessionService.getOrCreateByJwkThumbprint(thumbprint);
            session.setAccountId(knownAccountId.get());
            return flowSessionService.save(session);
        }

        return flowSessionService.getOrCreateByJwkThumbprint(thumbprint);
    }

    private NextStep resolveInitialNextStep(ClientSession session) {
        if ("authenticated".equals(session.getPhase())) {
            return new NextStep.AuthenticationCompletedNextStep();
        }

        if (session.getPendingChallenge() != null) {
            return challengeNextStep(session);
        }

        Long accountId = session.getAccountId();
        if (accountId != null) {
            if (accountService.hasActiveAuthenticationMethod(accountId)) {
                return new NextStep.AuthenticationMethodSelectionNextStep(
                        authenticationMethodProvider.activeMethods(accountService.findById(accountId).orElseThrow()));
            }
            return new NextStep.AuthenticationSetupNextStep(authenticationMethodProvider.availableMethods());
        }

        Long personId = session.getPersonId();
        if (personId != null) {
            return new NextStep.FscInputNextStep();
        }

        return new NextStep.UseIdentificationMethodNextStep(identificationMethodProvider.availableMethods());
    }

    private NextStep challengeNextStep(ClientSession session) {
        Map<String, Object> pendingChallenge = session.getPendingChallenge();
        String method = String.valueOf(pendingChallenge.get("method"));
        Long challengeId = ((Number) pendingChallenge.get("challengeId")).longValue();
        String tan = String.valueOf(pendingChallenge.get("tan"));
        if ("sms".equals(method)) {
            return new NextStep.SmsTanInputNextStep(challengeId, tan);
        }
        throw new IllegalStateException("Unsupported pending challenge method: " + method);
    }

    @Transactional
    public FlowSetupResponse startIdentification(UUID sessionId, String thumbprint, String method, Map<String, Object> request) {
        IdentificationMethodHandler handler = identificationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }
        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        NextStep next = handler.start(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse submitIdentification(UUID sessionId, String thumbprint, String method, Map<String, Object> request) {
        IdentificationMethodHandler handler = identificationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }
        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        NextStep next = handler.submit(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse startAuthentication(UUID sessionId, String thumbprint, String method, Map<String, Object> request) {
        AuthenticationMethodHandler handler = authenticationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }
        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        NextStep next = handler.start(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse verifyAuthentication(UUID sessionId, String thumbprint, String method, Map<String, Object> request) {
        AuthenticationMethodHandler handler = authenticationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }
        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        NextStep next = handler.verify(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }
}
