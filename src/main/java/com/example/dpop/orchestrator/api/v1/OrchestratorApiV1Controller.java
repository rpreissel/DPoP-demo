package com.example.dpop.orchestrator.api.v1;

import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/api/v1")
public class OrchestratorApiV1Controller {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final SessionManagementService sessionManagementService;
    private final OrchestratorApiV1Service orchestratorApiV1Service;

    public OrchestratorApiV1Controller(
            DpopValidator dpopValidator,
            JwkThumbprintService jwkThumbprintService,
            SessionManagementService sessionManagementService,
            OrchestratorApiV1Service orchestratorApiV1Service
    ) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.sessionManagementService = sessionManagementService;
        this.orchestratorApiV1Service = orchestratorApiV1Service;
    }

    // Channel entry point
    @PostMapping("/channel")
    public ResponseEntity<OrchestratorResponse> initializeChannel(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody(required = false) ChannelSessionRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        ChannelSession.Channel channel = request != null && "WEB".equalsIgnoreCase(request.channel())
                ? ChannelSession.Channel.WEB
                : ChannelSession.Channel.APP;

        ChannelSession channelSession = sessionManagementService.getOrCreateChannelSession(
                bindingKeyRef,
                channel,
                Duration.ofHours(1)
        );

        OrchestratorResponse response = orchestratorApiV1Service.initializeFlow(channelSession);
        return ResponseEntity.ok(response);
    }

    // Identification attempt endpoints
    @PostMapping("/attempts/identification")
    public ResponseEntity<OrchestratorResponse> startIdentification(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody AttemptRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.startIdentification(bindingKeyRef, request.method(), request.data());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/attempts/identification/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitIdentificationData(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.submitIdentificationData(attemptId, bindingKeyRef, data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/attempts/identification/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getIdentificationStatus(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.getIdentificationStatus(attemptId, bindingKeyRef);
        return ResponseEntity.ok(response);
    }

    // Authentication attempt endpoints
    @PostMapping("/attempts/authentication")
    public ResponseEntity<OrchestratorResponse> startAuthentication(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody AttemptRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.startAuthentication(bindingKeyRef, request.method(), request.data());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/attempts/authentication/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitAuthenticationData(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.submitAuthenticationData(attemptId, bindingKeyRef, data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/attempts/authentication/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getAuthenticationStatus(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = orchestratorApiV1Service.getAuthenticationStatus(attemptId, bindingKeyRef);
        return ResponseEntity.ok(response);
    }

    // Exception handlers
    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
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
