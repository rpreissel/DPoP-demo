package com.example.dpop.orchestrator.api.v1.identification.fsc;

import com.example.dpop.orchestrator.api.v1.DpopBaseController;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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
public class FscIdentificationController extends DpopBaseController {

    private final FscIdentificationService fscService;

    public FscIdentificationController(
            DpopValidator dpopValidator,
            JwkThumbprintService jwkThumbprintService,
            FscIdentificationService fscService
    ) {
        super(dpopValidator, jwkThumbprintService);
        this.fscService = fscService;
    }

    @PostMapping("/app/channels/{channelSessionId}/identification-methods/fsc/attempts")
    public ResponseEntity<OrchestratorResponse> startIdentificationFsc(
            @PathVariable UUID channelSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody(required = false) Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = fscService.startIdentification(channelSessionId, bindingKeyRef, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/identification-methods/fsc/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> submitIdentificationDataFsc(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody Map<String, Object> data,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = fscService.submitIdentificationData(attemptId, bindingKeyRef, data);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/identification-methods/fsc/attempts/{attemptId}")
    public ResponseEntity<OrchestratorResponse> getIdentificationStatusFsc(
            @PathVariable UUID attemptId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        OrchestratorResponse response = fscService.getIdentificationStatus(attemptId, bindingKeyRef);
        return ResponseEntity.ok(response);
    }
}
