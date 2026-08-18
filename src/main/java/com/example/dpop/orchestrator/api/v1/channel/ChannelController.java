package com.example.dpop.orchestrator.api.v1.channel;

import com.example.dpop.orchestrator.api.v1.ChannelSessionRequest;
import com.example.dpop.orchestrator.api.v1.ChannelSessionResponse;
import com.example.dpop.orchestrator.api.v1.DpopBaseController;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.session.ChannelSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/api/v1")
public class ChannelController extends DpopBaseController {

    private final ChannelService channelService;

    public ChannelController(
            DpopValidator dpopValidator,
            JwkThumbprintService jwkThumbprintService,
            ChannelService channelService
    ) {
        super(dpopValidator, jwkThumbprintService);
        this.channelService = channelService;
    }

    @PostMapping("/app/channels")
    public ResponseEntity<OrchestratorResponse> createChannel(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody(required = false) ChannelSessionRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        ChannelSession.Channel channel = request != null && "WEB".equalsIgnoreCase(request.channel())
                ? ChannelSession.Channel.WEB
                : ChannelSession.Channel.APP;

        OrchestratorResponse response = channelService.initializeFlow(bindingKeyRef, channel);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/app/channels/{channelSessionId}")
    public ResponseEntity<ChannelSessionResponse> getChannel(
            @PathVariable UUID channelSessionId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest httpRequest) {

        validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        ChannelSessionResponse response = channelService.getChannelSession(channelSessionId);
        return ResponseEntity.ok(response);
    }

    // Legacy path (kept for backward compatibility)
    @PostMapping("/channel")
    public ResponseEntity<OrchestratorResponse> initializeChannel(
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody(required = false) ChannelSessionRequest request,
            HttpServletRequest httpRequest) {

        String bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest);
        ChannelSession.Channel channel = request != null && "WEB".equalsIgnoreCase(request.channel())
                ? ChannelSession.Channel.WEB
                : ChannelSession.Channel.APP;

        OrchestratorResponse response = channelService.initializeFlow(bindingKeyRef, channel);
        return ResponseEntity.ok(response);
    }
}
