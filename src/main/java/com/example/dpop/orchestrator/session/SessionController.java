package com.example.dpop.orchestrator.session;

import com.example.dpop.account.AccountService;
import com.example.dpop.account.AuthenticationMethod;
import com.example.dpop.orchestrator.account.AccountJwkMappingService;
import com.example.dpop.orchestrator.authorisation.AuthorisationSessionService;
import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.registration.RegistrationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/sessions")
public class SessionController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final RegistrationSessionService registrationSessionService;
    private final AuthorisationSessionService authorisationSessionService;
    private final AccountJwkMappingService accountJwkMappingService;
    private final AccountService accountService;

    public SessionController(DpopValidator dpopValidator,
                             JwkThumbprintService jwkThumbprintService,
                             RegistrationSessionService registrationSessionService,
                             AuthorisationSessionService authorisationSessionService,
                             AccountJwkMappingService accountJwkMappingService,
                             AccountService accountService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.registrationSessionService = registrationSessionService;
        this.authorisationSessionService = authorisationSessionService;
        this.accountJwkMappingService = accountJwkMappingService;
        this.accountService = accountService;
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
        Optional<ClientSession> authorisationSession = authorisationSessionService.findByJwkThumbprint(thumbprint);

        if (authorisationSession.isPresent()) {
            authorisationSessionService.deleteByJwkThumbprint(thumbprint);
        }

        if (knownAccountId.isPresent() && accountService.hasActiveAuthenticationMethod(knownAccountId.get())) {
            ClientSession session = authorisationSessionService.createSession(thumbprint, knownAccountId.get());
            NextStep nextStep = resolveAuthorisationNextStep(knownAccountId.get());
            return ResponseEntity.ok(new SessionStatusResponse(
                    null,
                    session.getSessionId(),
                    nextStep
            ));
        }

        Optional<ClientSession> registrationSession = registrationSessionService.findByJwkThumbprint(thumbprint);
        if (registrationSession.isPresent()) {
            ClientSession session = registrationSession.get();
            Long accountId = session.getAccountId();
            if (accountId != null && accountService.hasActiveAuthenticationMethod(accountId)) {
                ClientSession authSession = authorisationSessionService.createSession(thumbprint, accountId);
                NextStep nextStep = resolveAuthorisationNextStep(accountId);
                return ResponseEntity.ok(new SessionStatusResponse(
                        null,
                        authSession.getSessionId(),
                        nextStep
                ));
            }
            return ResponseEntity.ok(new SessionStatusResponse(
                    session.getSessionId(),
                    null,
                    resolveRegistrationNextStep(session)
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

    private NextStep resolveAuthorisationNextStep(Long accountId) {
        return accountService.findById(accountId)
                .filter(account -> account.getAuthenticationMethods().stream().anyMatch(AuthenticationMethod::isActive))
                .map(account -> {
                    List<String> methods = account.getAuthenticationMethods().stream()
                            .filter(AuthenticationMethod::isActive)
                            .map(AuthenticationMethod::getMethod)
                            .distinct()
                            .toList();
                    return new NextStep.AuthenticationMethodSelectionNextStep(methods);
                })
                .orElse(null);
    }

    private NextStep resolveRegistrationNextStep(ClientSession session) {
        if (session.getAccountId() != null) {
            return new NextStep.AuthenticationSetupNextStep(List.of("sms"));
        }
        if (session.getPersonId() != null) {
            return new NextStep.FscInputNextStep();
        }
        return new NextStep.UseIdentificationMethodNextStep(List.of("fsc"));
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
