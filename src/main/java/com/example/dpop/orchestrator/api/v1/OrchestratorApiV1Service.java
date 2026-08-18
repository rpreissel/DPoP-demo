package com.example.dpop.orchestrator.api.v1;

import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.AuthenticationAttempt;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.ChannelState;
import com.example.dpop.orchestrator.session.IdentificationAttempt;
import com.example.dpop.orchestrator.session.LoginProcessSession;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessSession;
import com.example.dpop.orchestrator.session.RegistrationProcessSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
            // Existing account: offer authentication
            sessionManagementService.updateChannelState(channelSession.getChannelSessionId(), ChannelState.AUTHENTICATED);
            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.NextRouting("authentication", "selectMethod", Arrays.asList("sms"))
            );
        } else {
            // New session: start registration
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
            attempt.setMissingFields(serializeList(Arrays.asList("kvnr", "name")));
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

    // FSC Identification Flow
    public OrchestratorResponse startIdentification(String bindingKeyRef, String method, Map<String, Object> data) {
        if (!"fsc".equals(method)) {
            throw new IllegalArgumentException("Only fsc method is supported for identification");
        }

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        RegistrationProcessSession processSession = (RegistrationProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        processSession.setSelectedIdentificationMethod("fsc");
        sessionManagementService.updateProcessSession(processSession);

        IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                processSession.getProcessSessionId(),
                "fsc",
                "input",
                Duration.ofMinutes(10)
        );

        List<String> missingFields = new ArrayList<>();
        if (data == null || !data.containsKey("kvnr")) missingFields.add("kvnr");
        if (data == null || !data.containsKey("name")) missingFields.add("name");

        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(missingFields));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "identification",
                        "INPUT_REQUIRED",
                        missingFields,
                        null
                ),
                new OrchestratorResponse.NextRouting("fsc", "input")
        );
    }

    public OrchestratorResponse submitIdentificationData(UUID attemptId, String bindingKeyRef, Map<String, Object> data) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        // Validate data
        List<String> missingFields = new ArrayList<>();
        if (data == null || !data.containsKey("kvnr")) missingFields.add("kvnr");
        if (data == null || !data.containsKey("name")) missingFields.add("name");

        if (!missingFields.isEmpty()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(missingFields));
            sessionManagementService.updateAttempt(attempt);

            return new OrchestratorResponse(
                    null,
                    null,
                    new OrchestratorResponse.AttemptState(
                            attemptId,
                            "identification",
                            "INPUT_REQUIRED",
                            missingFields,
                            null
                    ),
                    new OrchestratorResponse.NextRouting("fsc", "input")
            );
        }

        // Mock verification - in real implementation, would call ID verification service
        Long personId = 5001L;

        attempt.setStatus(AttemptStatus.VERIFIED);
        Map<String, Object> result = new HashMap<>();
        result.put("identified", true);
        result.put("personId", personId);
        attempt.setResult("{ \"identified\": true, \"personId\": 5001 }");
        sessionManagementService.updateAttempt(attempt);

        // Update process session with person ID
        ProcessSession processSession = sessionManagementService.getProcessSessionById(attempt.getProcessSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));
        processSession.setPersonId(personId);
        sessionManagementService.updateProcessSession(processSession);

        // Move to SMS enrollment
        return new OrchestratorResponse(
                null,
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, null),
                new OrchestratorResponse.AttemptState(
                        attemptId,
                        "identification",
                        "VERIFIED",
                        null,
                        result
                ),
                new OrchestratorResponse.NextRouting("enrollment", "selectMethod", Arrays.asList("sms"))
        );
    }

    public OrchestratorResponse getIdentificationStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        Object result = null;
        if (attempt.getResult() != null && !attempt.getResult().isEmpty()) {
            result = attempt.getResult();
        }

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "identification",
                        attempt.getStatus().toString(),
                        null,
                        result
                ),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    // SMS Authentication - Enroll Flow
    public OrchestratorResponse startAuthenticationWithMode(String bindingKeyRef, String method, String mode, Map<String, Object> data) {
        if (!"sms".equals(method)) {
            throw new IllegalArgumentException("Only sms method is supported for authentication");
        }

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        LoginProcessSession processSession = (LoginProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                .orElseGet(() -> sessionManagementService.createLoginProcessSession(
                        channelSession.getChannelSessionId(),
                        Duration.ofMinutes(15)
                ));

        processSession.setSelectedAuthenticationMethod(method);
        sessionManagementService.updateProcessSession(processSession);

        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(),
                method,
                mode.equals("enroll") ? "enroll" : "use",
                Duration.ofMinutes(10)
        );

        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(Arrays.asList("phoneNumber")));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("LOGIN", "ACTIVE", null, null),
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "authentication",
                        "INPUT_REQUIRED",
                        Arrays.asList("phoneNumber"),
                        null
                ),
                new OrchestratorResponse.NextRouting("sms", mode.equals("enroll") ? "enroll" : "use")
        );
    }

    public OrchestratorResponse submitAuthenticationDataWithMode(UUID attemptId, String bindingKeyRef, String method, String mode, Map<String, Object> data) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        // First submission: collect phone number
        List<String> missingFields = new ArrayList<>();
        if (data == null || !data.containsKey("tan")) {
            if (data == null || !data.containsKey("phoneNumber")) {
                missingFields.add("phoneNumber");
            } else {
                missingFields.add("tan");
            }
        }

        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(missingFields));
        attempt.setNextStep(missingFields.contains("tan") ? "tanInput" : "enroll");
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attemptId,
                        "authentication",
                        "INPUT_REQUIRED",
                        missingFields,
                        null
                ),
                new OrchestratorResponse.NextRouting("sms", missingFields.contains("tan") ? "tanInput" : "enroll")
        );
    }

    public OrchestratorResponse getAuthenticationStatusWithMode(UUID attemptId, String bindingKeyRef, String method, String mode) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        Object result = null;
        if (attempt.getResult() != null && !attempt.getResult().isEmpty()) {
            result = attempt.getResult();
        }

        return new OrchestratorResponse(
                null,
                null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(),
                        "authentication",
                        attempt.getStatus().toString(),
                        null,
                        result
                ),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    // Legacy methods - kept for backward compatibility
    public OrchestratorResponse startAuthentication(String bindingKeyRef, String method, Map<String, Object> data) {
        return startAuthenticationWithMode(bindingKeyRef, method, "use", data);
    }

    public OrchestratorResponse submitAuthenticationData(UUID attemptId, String bindingKeyRef, Map<String, Object> data) {
        return submitAuthenticationDataWithMode(attemptId, bindingKeyRef, "sms", "use", data);
    }

    public OrchestratorResponse getAuthenticationStatus(UUID attemptId, String bindingKeyRef) {
        return getAuthenticationStatusWithMode(attemptId, bindingKeyRef, "sms", "use");
    }

    // Helper methods
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
