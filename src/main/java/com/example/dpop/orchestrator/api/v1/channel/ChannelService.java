package com.example.dpop.orchestrator.api.v1.channel;

import com.example.dpop.orchestrator.api.v1.ChannelSessionResponse;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.ChannelState;
import com.example.dpop.orchestrator.session.IdentificationAttempt;
import com.example.dpop.orchestrator.session.RegistrationProcessSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ChannelService {

    private final SessionManagementService sessionManagementService;

    public ChannelService(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    public OrchestratorResponse initializeFlow(String bindingKeyRef, ChannelSession.Channel channel) {
        ChannelSession channelSession = sessionManagementService.getOrCreateChannelSession(
                bindingKeyRef,
                channel,
                Duration.ofHours(1)
        );
        return initializeFlow(channelSession);
    }

    public OrchestratorResponse initializeFlow(ChannelSession channelSession) {
        sessionManagementService.updateChannelSession(channelSession);

        if (channelSession.getAccountId() != null) {
            sessionManagementService.updateChannelState(channelSession.getChannelSessionId(), ChannelState.AUTHENTICATED);
            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.NextRouting("authentication", "selectMethod", Arrays.asList("sms"))
            );
        } else {
            sessionManagementService.updateChannelState(channelSession.getChannelSessionId(), ChannelState.REGISTERING);
            RegistrationProcessSession processSession = sessionManagementService.createRegistrationProcessSession(
                    channelSession.getChannelSessionId(),
                    Duration.ofMinutes(15)
            );

            IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                    processSession.getProcessSessionId(),
                    "fsc",
                    "input",
                    Duration.ofMinutes(10)
            );
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(Arrays.asList("kvnr", "fsc")));
            sessionManagementService.updateAttempt(attempt);

            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                    new OrchestratorResponse.AttemptState(
                            attempt.getAttemptId(),
                            "identification",
                            "INPUT_REQUIRED",
                            Arrays.asList("kvnr", "name"),
                            null
                    ),
                    new OrchestratorResponse.NextRouting(
                            "fsc",
                            "input",
                            null
                    )
            );
        }
    }

    public ChannelSessionResponse getChannelSession(UUID channelSessionId) {
        ChannelSession channelSession = sessionManagementService.getChannelSessionById(channelSessionId)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        List<String> currentAmr = null;
        String currentAcr = null;
        if (channelSession.getAuthContextId() != null) {
            // TODO: Load AuthContext and extract ACR/AMR
        }

        return new ChannelSessionResponse(
                channelSession.getChannelSessionId(),
                channelSession.getState(),
                currentAcr,
                currentAmr,
                channelSession.getState() == ChannelState.STEP_UP_REQUIRED,
                channelSession.getAccountId()
        );
    }

    private String serializeList(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(list.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
