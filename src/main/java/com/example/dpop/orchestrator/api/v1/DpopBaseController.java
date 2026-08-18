package com.example.dpop.orchestrator.api.v1;

import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

public abstract class DpopBaseController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;

    protected DpopBaseController(DpopValidator dpopValidator, JwkThumbprintService jwkThumbprintService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
    }

    protected String validateAndExtractBindingKeyRef(String dpopProof, HttpServletRequest request) {
        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        return jwkThumbprintService.computeThumbprint(proof.publicKey());
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

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
