package com.example.dpop.orchestrator.session;

import com.example.dpop.account.AccountService;
import com.example.dpop.orchestrator.account.AccountJwkMappingService;
import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/sessions")
public class SessionController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final ClientFlowSessionService flowSessionService;
    private final AccountJwkMappingService accountJwkMappingService;
    private final AccountService accountService;
    private final FlowNextStepResolver nextStepResolver;

    public SessionController(DpopValidator dpopValidator,
                             JwkThumbprintService jwkThumbprintService,
                             ClientFlowSessionService flowSessionService,
                             AccountJwkMappingService accountJwkMappingService,
                             AccountService accountService,
                             FlowNextStepResolver nextStepResolver) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.flowSessionService = flowSessionService;
        this.accountJwkMappingService = accountJwkMappingService;
        this.accountService = accountService;
        this.nextStepResolver = nextStepResolver;
    }

    @GetMapping
    @Transactional
    public ResponseEntity<SessionStatusResponse> findSessions(
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());

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
                session = flowSessionService.save(session);
                NextStep nextStep = nextStepResolver.resolve(session);
                return ResponseEntity.ok(new SessionStatusResponse(
                        null,
                        session.getSessionId(),
                        nextStep
                ));
            }

            NextStep nextStep = nextStepResolver.resolve(session);
            return ResponseEntity.ok(new SessionStatusResponse(
                    session.getSessionId(),
                    null,
                    nextStep
            ));
        }

        if (knownAccountId.isPresent() && accountService.hasActiveAuthenticationMethod(knownAccountId.get())) {
            ClientSession session = flowSessionService.getOrCreateByJwkThumbprint(thumbprint);
            session.setAccountId(knownAccountId.get());
            session = flowSessionService.save(session);
            NextStep nextStep = nextStepResolver.resolve(session);
            return ResponseEntity.ok(new SessionStatusResponse(
                    null,
                    session.getSessionId(),
                    nextStep
            ));
        }

        return ResponseEntity.ok(new SessionStatusResponse(
                null,
                null,
                new NextStep.RegistrationNextStep()
        ));
    }

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    private String buildRequestUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String path = request.getRequestURI();

        StringBuilder url = new StringBuilder(scheme).append("://").append(host);
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            url.append(":").append(port);
        }
        url.append(path);
        return url.toString();
    }
}
