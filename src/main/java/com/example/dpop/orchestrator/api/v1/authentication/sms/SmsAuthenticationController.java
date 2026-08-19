package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.orchestrator.api.v1.AttemptRequest;
import com.example.dpop.orchestrator.api.v1.DpopBaseController;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/orchestrator/api/v1")
public class SmsAuthenticationController extends DpopBaseController {

    private final SmsAuthenticationService smsService;

    public SmsAuthenticationController(
            DpopValidator dpopValidator,
            JwkThumbprintService jwkThumbprintService,
            SmsAuthenticationService smsService
    ) {
        super(dpopValidator, jwkThumbprintService);
        this.smsService = smsService;
    }

    // New paths - SMS Enroll
    @PostMapping("/app/channels/{channelSessionId}/authentication-methods/sms/enroll/attempts")
    public ResponseEntity<OrchestratorResponse> startAuthenticationSmsEnroll(
            @PathVariable UUID channelSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.startAuthentication(channelSessionId, bindingKeyRef, "enroll", data);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/app/channels/{channelSessionId}/authentication-methods/sms/use/attempts")
    public ResponseEntity<OrchestratorResponse> startAuthenticationSmsUse(
            @PathVariable UUID channelSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.startAuthentication(channelSessionId, bindingKeyRef, "use", data);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/authentication-methods/sms/enroll/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitAuthenticationDataSmsEnroll(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.submitAuthentication(attemptId, bindingKeyRef, "enroll", data);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/authentication-methods/sms/use/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitAuthenticationDataSmsUse(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.submitAuthentication(attemptId, bindingKeyRef, "use", data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/authentication-methods/sms/enroll/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getAuthenticationStatusSmsEnroll(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.getAuthenticationStatus(attemptId, bindingKeyRef, "enroll");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/authentication-methods/sms/use/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getAuthenticationStatusSmsUse(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.getAuthenticationStatus(attemptId, bindingKeyRef, "use");
        return ResponseEntity.ok(response);
    }

    // Legacy paths (kept for backward compatibility)
    @PostMapping("/attempts/authentication")
    public ResponseEntity<OrchestratorResponse> startAuthentication(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody AttemptRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.startAuthentication(null, bindingKeyRef, "use", request.data());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/attempts/authentication/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitAuthenticationData(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.submitAuthentication(attemptId, bindingKeyRef, "use", data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/attempts/authentication/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getAuthenticationStatus(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = smsService.getAuthenticationStatus(attemptId, bindingKeyRef, "use");
        return ResponseEntity.ok(response);
    }
}
