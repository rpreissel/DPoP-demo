package com.example.dpop.orchestrator.flow;

import com.example.dpop.account.AccountService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
import com.example.dpop.orchestrator.flow.handler.FscIdentificationHandler;
import com.example.dpop.orchestrator.flow.handler.SmsAuthenticationHandler;
import com.example.dpop.orchestrator.session.AuthenticationMethodProvider;
import com.example.dpop.orchestrator.session.BindingFlowSessionService;
import com.example.dpop.orchestrator.session.BindingSession;
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

    private final BindingFlowSessionService flowSessionService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;
    private final IdentificationMethodProvider identificationMethodProvider;
    private final AuthenticationMethodProvider authenticationMethodProvider;
    private final Map<String, IdentificationMethodHandler> identificationHandlers;
    private final Map<String, AuthenticationMethodHandler> authenticationHandlers;

    public FlowActionService(BindingFlowSessionService flowSessionService,
                             AccountService accountService,
                             AccountBindingKeyMappingService accountBindingKeyMappingService,
                             IdentificationMethodProvider identificationMethodProvider,
                             AuthenticationMethodProvider authenticationMethodProvider,
                             List<IdentificationMethodHandler> identificationHandlers,
                             List<AuthenticationMethodHandler> authenticationHandlers) {
        this.flowSessionService = flowSessionService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
        this.identificationMethodProvider = identificationMethodProvider;
        this.authenticationMethodProvider = authenticationMethodProvider;
        this.identificationHandlers = identificationHandlers.stream()
                .collect(Collectors.toMap(IdentificationMethodHandler::method, Function.identity()));
        this.authenticationHandlers = authenticationHandlers.stream()
                .collect(Collectors.toMap(AuthenticationMethodHandler::method, Function.identity()));
    }

    @Transactional
    public FlowSetupResponse createFlow(String bindingKeyRef) {
        BindingSession session = flowSessionService.createNewByBindingKeyRef(bindingKeyRef);
        Optional<Long> knownAccountId = accountBindingKeyMappingService.findAccountIdByBindingKeyRef(bindingKeyRef);
        if (knownAccountId.isPresent() && accountService.hasActiveAuthenticationMethod(knownAccountId.get())) {
            session.setAccountId(knownAccountId.get());
            session = flowSessionService.save(session);
        }
        return new FlowSetupResponse(session.getSessionId(), resolveInitialNextStep(session));
    }

    private NextStep resolveInitialNextStep(BindingSession session) {
        if ("authenticated".equals(session.getPhase())) {
            return authenticatedNextStep(session);
        }

        if (session.getPendingChallenge() != null) {
            return challengeNextStep(session);
        }

        Long accountId = session.getAccountId();
        if (accountId != null) {
            if (accountService.hasActiveAuthenticationMethod(accountId)) {
                return new NextStep.AuthenticationMethodSelectionNextStep(
                        accountService.findActiveAuthenticationMethods(accountId));
            }
            return new NextStep.AuthenticationSetupNextStep(authenticationMethodProvider.availableMethods());
        }

        Long personId = session.getPersonId();
        if (personId != null) {
            return new NextStep.FscInputNextStep();
        }

        return new NextStep.UseIdentificationMethodNextStep(identificationMethodProvider.availableMethods());
    }

    private NextStep challengeNextStep(BindingSession session) {
        Map<String, Object> pendingChallenge = session.getPendingChallenge();
        String method = String.valueOf(pendingChallenge.get("method"));
        Long challengeId = ((Number) pendingChallenge.get("challengeId")).longValue();
        String tan = String.valueOf(pendingChallenge.get("tan"));
        if ("sms".equals(method)) {
            return new NextStep.SmsTanInputNextStep(challengeId, tan);
        }
        throw new IllegalStateException("Unsupported pending challenge method: " + method);
    }

    private NextStep authenticatedNextStep(BindingSession session) {
        Long accountId = session.getAccountId();
        Long personId = accountId == null
                ? null
                : accountService.findAccountProfile(accountId).map(profile -> profile.personId()).orElse(null);
        return new NextStep.AuthenticationCompletedNextStep(accountId, personId);
    }

    @Transactional
    public FlowSetupResponse startIdentification(UUID sessionId, String bindingKeyRef, String method, Map<String, Object> request) {
        IdentificationMethodHandler handler = identificationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }
        BindingSession session = flowSessionService.requireSession(sessionId, bindingKeyRef);
        NextStep next = handler.start(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse submitIdentification(UUID sessionId, String bindingKeyRef, String method, Map<String, Object> request) {
        IdentificationMethodHandler handler = identificationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }
        BindingSession session = flowSessionService.requireSession(sessionId, bindingKeyRef);
        NextStep next = handler.submit(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse startAuthentication(UUID sessionId, String bindingKeyRef, String method, Map<String, Object> request) {
        AuthenticationMethodHandler handler = authenticationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }
        BindingSession session = flowSessionService.requireSession(sessionId, bindingKeyRef);
        NextStep next = handler.start(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }

    @Transactional
    public FlowSetupResponse verifyAuthentication(UUID sessionId, String bindingKeyRef, String method, Map<String, Object> request) {
        AuthenticationMethodHandler handler = authenticationHandlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }
        BindingSession session = flowSessionService.requireSession(sessionId, bindingKeyRef);
        NextStep next = handler.verify(session, request);
        flowSessionService.save(session);
        return new FlowSetupResponse(next);
    }
}
