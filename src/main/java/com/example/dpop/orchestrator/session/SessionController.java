package com.example.dpop.orchestrator.session;

import com.example.dpop.orchestrator.authorisation.AuthorisationSessionService;
import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.registration.RegistrationSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final RegistrationSessionService registrationSessionService;
    private final AuthorisationSessionService authorisationSessionService;

    public SessionController(DpopValidator dpopValidator,
                             JwkThumbprintService jwkThumbprintService,
                             RegistrationSessionService registrationSessionService,
                             AuthorisationSessionService authorisationSessionService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.registrationSessionService = registrationSessionService;
        this.authorisationSessionService = authorisationSessionService;
    }

    @GetMapping
    public ResponseEntity<SessionStatusResponse> findSessions(
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());

        Optional<ClientSession> registrationSession = registrationSessionService.findByJwkThumbprint(thumbprint);
        Optional<ClientSession> authorisationSession = authorisationSessionService.findByJwkThumbprint(thumbprint);

        if (authorisationSession.isPresent()) {
            return ResponseEntity.ok(new SessionStatusResponse(
                    null,
                    authorisationSession.map(ClientSession::getSessionId).orElse(null),
                    null
            ));
        }

        if (registrationSession.isPresent()) {
            return ResponseEntity.ok(new SessionStatusResponse(
                    registrationSession.map(ClientSession::getSessionId).orElse(null),
                    null,
                    null
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
