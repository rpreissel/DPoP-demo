package com.example.dpop.orchestrator.api.v1;

import com.example.dpop.orchestrator.session.AuthenticationAttempt;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.IdentificationAttempt;
import com.example.dpop.orchestrator.session.LoginProcessSession;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessSession;
import com.example.dpop.orchestrator.session.RegistrationProcessSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class OrchestratorApiV1Service {

    private final SessionManagementService sessionManagementService;

    public OrchestratorApiV1Service(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    public OrchestratorResponse initializeFlow(ChannelSession channelSession) {
        sessionManagementService.updateChannelSession(channelSession);

        // Determine next step based on channel state
        if (channelSession.getAccountId() != null) {
            // Existing account: offer login or step-up
            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.NextRouting("authentication", "selectMethod", Arrays.asList("sms"))
            );
        } else {
            // New session: start registration
            RegistrationProcessSession processSession = sessionManagementService.createRegistrationProcessSession(
                    channelSession.getChannelSessionId(),
                    Duration.ofMinutes(15)
            );

            IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                    processSession.getProcessSessionId(),
                    "registration",
                    "selectMethod",
                    Duration.ofMinutes(10)
            );

            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                    new OrchestratorResponse.AttemptState(
                            attempt.getAttemptId(),
                            "IDENTIFICATION",
                            "ACTIVE",
                            null,
                            null
                    ),
                    new OrchestratorResponse.NextRouting(
                            "registration",
                            "selectMethod",
                            Arrays.asList("fsc")
                    )
            );
        }
    }

    public OrchestratorResponse startIdentification(String bindingKeyRef, String method, Map<String, Object> data) {
        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        RegistrationProcessSession processSession = (RegistrationProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        processSession.setSelectedIdentificationMethod(method);
        sessionManagementService.updateProcessSession(processSession);

        IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                processSession.getProcessSessionId(),
                method,
                "input",
                Duration.ofMinutes(10)
        );

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "IDENTIFICATION",
                        "ACTIVE",
                        Arrays.asList("kvnr", "name"),
                        null
                ),
                new OrchestratorResponse.NextRouting(method, "input")
        );
    }

    public OrchestratorResponse submitIdentificationData(UUID attemptId, String bindingKeyRef, Map<String, Object> data) {
        OrchestratorAttempt attempt = sessionManagementService.getLatestAttemptForProcessSession(null)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        // Validate data and update attempt
        attempt.setStatus(OrchestratorAttempt.AttemptStatus.COMPLETED);
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "IDENTIFICATION",
                        "COMPLETED",
                        null,
                        Map.of("personId", 1L)
                ),
                new OrchestratorResponse.NextRouting("authentication", "selectMethod", Arrays.asList("sms"))
        );
    }

    public OrchestratorResponse getIdentificationStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getLatestAttemptForProcessSession(null)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "IDENTIFICATION",
                        attempt.getStatus().toString(),
                        null,
                        null
                ),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    public OrchestratorResponse startAuthentication(String bindingKeyRef, String method, Map<String, Object> data) {
        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        ProcessSession processSession = sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(),
                method,
                "setup",
                Duration.ofMinutes(10)
        );

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "AUTHENTICATION",
                        "ACTIVE",
                        Arrays.asList("phoneNumber"),
                        null
                ),
                new OrchestratorResponse.NextRouting(method, "setup")
        );
    }

    public OrchestratorResponse submitAuthenticationData(UUID attemptId, String bindingKeyRef, Map<String, Object> data) {
        OrchestratorAttempt attempt = sessionManagementService.getLatestAttemptForProcessSession(null)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        attempt.setStatus(OrchestratorAttempt.AttemptStatus.PENDING_VERIFICATION);
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "AUTHENTICATION",
                        "PENDING_VERIFICATION",
                        null,
                        null
                ),
                new OrchestratorResponse.NextRouting("sms", "tanInput")
        );
    }

    public OrchestratorResponse getAuthenticationStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getLatestAttemptForProcessSession(null)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "AUTHENTICATION",
                        attempt.getStatus().toString(),
                        null,
                        null
                ),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }
}
