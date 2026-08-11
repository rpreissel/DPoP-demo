package com.example.dpop.orchestrator.registration;

import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.session.NextStep;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/registration-sessions")
public class RegistrationController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final RegistrationSessionService sessionService;

    public RegistrationController(DpopValidator dpopValidator,
                                  JwkThumbprintService jwkThumbprintService,
                                  RegistrationSessionService sessionService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<RegistrationSetupResponse> setup(
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        UUID sessionId = sessionService.getOrCreateSession(proof, thumbprint);

        NextStep nextStep = new NextStep("useIdentificationMethod", List.of("fsc"));
        return ResponseEntity.ok(new RegistrationSetupResponse(sessionId, nextStep));
    }

    @PostMapping("/{registrationSessionId}/steps")
    public ResponseEntity<Map<String, String>> step(
            @PathVariable UUID registrationSessionId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        sessionService.requireSession(registrationSessionId, thumbprint);

        return ResponseEntity.ok(Map.of("status", "ok", "registrationSessionId", registrationSessionId.toString()));
    }

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RegistrationSessionException.class)
    public ResponseEntity<Map<String, String>> handleSessionException(RegistrationSessionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
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
