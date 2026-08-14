package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/sessions")
public class FlowController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final FlowActionService flowActionService;

    public FlowController(DpopValidator dpopValidator,
                          JwkThumbprintService jwkThumbprintService,
                          FlowActionService flowActionService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.flowActionService = flowActionService;
    }

    @PostMapping
    public ResponseEntity<FlowSetupResponse> createFlow(
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, request);
        return ResponseEntity.ok(flowActionService.createFlow(bindingKeyRef));
    }

    @PostMapping("/{sessionId}/identification-methods/{method}")
    public ResponseEntity<FlowSetupResponse> startIdentification(
            @PathVariable UUID sessionId,
            @PathVariable String method,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, request);
        return ResponseEntity.ok(flowActionService.startIdentification(sessionId, bindingKeyRef, method, requestBody));
    }

    @PatchMapping("/{sessionId}/identification-methods/{method}")
    public ResponseEntity<FlowSetupResponse> submitIdentification(
            @PathVariable UUID sessionId,
            @PathVariable String method,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, request);
        return ResponseEntity.ok(flowActionService.submitIdentification(sessionId, bindingKeyRef, method, requestBody));
    }

    @PostMapping("/{sessionId}/authentication-methods/{method}")
    public ResponseEntity<FlowSetupResponse> startAuthentication(
            @PathVariable UUID sessionId,
            @PathVariable String method,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody(required = false) Map<String, Object> requestBody,
            HttpServletRequest request) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, request);
        return ResponseEntity.ok(flowActionService.startAuthentication(sessionId, bindingKeyRef, method, requestBody));
    }

    @PostMapping("/{sessionId}/authentication-methods/{method}/verify")
    public ResponseEntity<FlowSetupResponse> verifyAuthentication(
            @PathVariable UUID sessionId,
            @PathVariable String method,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> requestBody,
            HttpServletRequest request) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, request);
        return ResponseEntity.ok(flowActionService.verifyAuthentication(sessionId, bindingKeyRef, method, requestBody));
    }

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(FlowSessionException.class)
    public ResponseEntity<Map<String, String>> handleSessionException(FlowSessionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    private String validateAndExtractBindingKeyRef(String dpopProof, HttpServletRequest request) {
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
}
