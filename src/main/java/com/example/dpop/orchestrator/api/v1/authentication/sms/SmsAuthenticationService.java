package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsChallengeResult;
import com.example.dpop.auth_sms.AuthSmsEnrollResult;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.orchestrator.api.v1.OrchestratorException;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.AuthenticationAttempt;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.ChannelState;
import com.example.dpop.orchestrator.session.LoginProcessSession;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessPurpose;
import com.example.dpop.orchestrator.session.ProcessSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class SmsAuthenticationService {

    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() {};

    private final SessionManagementService sessionManagementService;
    private final AccountService accountService;
    private final AuthSmsService authSmsService;
    private final ObjectMapper objectMapper;

    public SmsAuthenticationService(
            SessionManagementService sessionManagementService,
            AccountService accountService,
            AuthSmsService authSmsService,
            ObjectMapper objectMapper
    ) {
        this.sessionManagementService = sessionManagementService;
        this.accountService = accountService;
        this.authSmsService = authSmsService;
        this.objectMapper = objectMapper;
    }

    /** POST enroll: parameter-arm, returns INPUT_REQUIRED with missingFields: [phoneNumber] */
    public OrchestratorResponse startEnroll(UUID channelSessionId, String bindingKeyRef) {
        ChannelSession channelSession = getChannelSession(bindingKeyRef, channelSessionId);
        LoginProcessSession processSession = getOrCreateLoginSession(channelSession);

        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(), "sms", "enroll", Duration.ofMinutes(10));
        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(Arrays.asList("phoneNumber")));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(), null,
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                        "INPUT_REQUIRED", Arrays.asList("phoneNumber"), null),
                new OrchestratorResponse.NextRouting("sms", "enroll")
        );
    }

    /** POST use: parameter-arm, sends TAN immediately, returns INPUT_REQUIRED with missingFields: [tan] */
    public OrchestratorResponse startUse(UUID channelSessionId, String bindingKeyRef) {
        ChannelSession channelSession = getChannelSession(bindingKeyRef, channelSessionId);
        Long accountId = channelSession.getAccountId();
        if (accountId == null) {
            throw OrchestratorException.forbidden("No account bound to channel");
        }

        Long enrollmentId = accountService.findActiveSmsEnrollmentId(accountId)
                .orElseThrow(() -> new IllegalStateException("No active SMS enrollment found"));

        AuthSmsChallengeResult challenge = authSmsService.sendChallenge(enrollmentId);

        LoginProcessSession processSession = getOrCreateLoginSession(channelSession);
        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(), "sms", "use", Duration.ofMinutes(10));
        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(Arrays.asList("tan")));
        attempt.setResult(toPendingJson(Map.of("enrollmentId", enrollmentId)));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(), null,
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                        "INPUT_REQUIRED", Arrays.asList("tan"), Map.of("enrollmentId", enrollmentId)),
                new OrchestratorResponse.NextRouting("sms", "use"),
                new OrchestratorResponse.DemoHints(challenge.tan(),
                        "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
        );
    }

    /** PATCH enroll: accepts phoneNumber, then tan */
    public OrchestratorResponse submitEnroll(UUID attemptId, String bindingKeyRef, Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }

        Map<String, Object> pending = loadPendingData(attempt);
        if (patchData != null) pending.putAll(patchData);

        String phoneNumber = (String) pending.get("phoneNumber");
        String tan = (String) pending.get("tan");
        Long enrollmentId = toLong(pending.get("enrollmentId"));

        // Phase 1: need phoneNumber
        if (phoneNumber == null || phoneNumber.isBlank()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(Arrays.asList("phoneNumber")));
            attempt.setResult(toPendingJson(pending));
            sessionManagementService.updateAttempt(attempt);
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "authentication", "INPUT_REQUIRED",
                            Arrays.asList("phoneNumber"), null),
                    new OrchestratorResponse.NextRouting("sms", "enroll"));
        }

        // Phase 2: have phone, no TAN yet → enroll and send TAN
        if (enrollmentId == null) {
            AuthSmsEnrollResult smsResult = authSmsService.enrollSms(phoneNumber);
            enrollmentId = smsResult.enrollmentId();
            pending.put("enrollmentId", enrollmentId);
            tan = (String) patchData.get("tan"); // check if tan was also in this patch
        }

        if (tan == null || tan.isBlank()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(Arrays.asList("tan")));
            attempt.setResult(toPendingJson(pending));
            sessionManagementService.updateAttempt(attempt);

            OrchestratorResponse.DemoHints demoHints = null;
            if (patchData != null && patchData.containsKey("phoneNumber")) {
                // just enrolled — show TAN hint
                AuthSmsChallengeResult challenge = authSmsService.sendChallenge(enrollmentId);
                demoHints = new OrchestratorResponse.DemoHints(challenge.tan(),
                        "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response");
            }
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "authentication", "INPUT_REQUIRED",
                            Arrays.asList("tan"), Map.of("enrollmentId", enrollmentId)),
                    new OrchestratorResponse.NextRouting("sms", "enroll"),
                    demoHints);
        }

        // Phase 3: verify TAN
        return verifyTan(attempt, enrollmentId, tan, bindingKeyRef, "enroll");
    }

    /** PATCH use: accepts tan */
    public OrchestratorResponse submitUse(UUID attemptId, String bindingKeyRef, Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }

        Map<String, Object> pending = loadPendingData(attempt);
        if (patchData != null) pending.putAll(patchData);

        String tan = (String) pending.get("tan");
        Long enrollmentId = toLong(pending.get("enrollmentId"));

        if (enrollmentId == null) {
            throw new IllegalStateException("No enrollmentId stored in attempt");
        }

        if (tan == null || tan.isBlank()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(Arrays.asList("tan")));
            attempt.setResult(toPendingJson(pending));
            sessionManagementService.updateAttempt(attempt);
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "authentication", "INPUT_REQUIRED",
                            Arrays.asList("tan"), Map.of("enrollmentId", enrollmentId)),
                    new OrchestratorResponse.NextRouting("sms", "use"));
        }

        return verifyTan(attempt, enrollmentId, tan, bindingKeyRef, "use");
    }

    private OrchestratorResponse verifyTan(OrchestratorAttempt attempt, Long enrollmentId, String tan,
            String bindingKeyRef, String mode) {
        UUID attemptId = attempt.getAttemptId();

        try {
            authSmsService.validateTan(enrollmentId, tan);
        } catch (Exception e) {
            throw OrchestratorException.verificationFailed("TAN validation failed");
        }

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> OrchestratorException.forbidden("Channel session not found"));
        Long accountId = channelSession.getAccountId();

        if (accountId != null && "enroll".equals(mode)) {
            accountService.addAuthenticationMethod(accountId, "sms", true,
                    Map.of("enrollmentId", enrollmentId, "enrollmentRef", enrollmentId));
        }

        attempt.setStatus(AttemptStatus.VERIFIED);
        attempt.setResult("{ \"verified\": true }");
        sessionManagementService.updateAttempt(attempt);
        sessionManagementService.updateChannelState(channelSession.getChannelSessionId(), ChannelState.AUTHENTICATED);

        ProcessSession processSession = sessionManagementService.getProcessSessionById(attempt.getProcessSessionId())
                .orElse(null);
        Long personId = processSession != null ? processSession.getPersonId() : null;

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "COMPLETED", personId, accountId),
                new OrchestratorResponse.AttemptState(attemptId, "authentication", "VERIFIED", null,
                        Map.of("verified", true)),
                new OrchestratorResponse.NextRouting("authentication", "authenticated", null,
                        null, accountId, personId)
        );
    }

    public OrchestratorResponse getStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        Object result = null;
        if (attempt.getStatus() == AttemptStatus.VERIFIED && attempt.getResult() != null) {
            result = attempt.getResult();
        }

        return new OrchestratorResponse(null, null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(), "authentication", attempt.getStatus().toString(), null, result),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    private ChannelSession getChannelSession(String bindingKeyRef, UUID channelSessionId) {
        ChannelSession cs = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> OrchestratorException.forbidden("Channel session not found"));
        if (channelSessionId != null && !cs.getChannelSessionId().equals(channelSessionId)) {
            throw OrchestratorException.forbidden("Channel session mismatch");
        }
        return cs;
    }

    private LoginProcessSession getOrCreateLoginSession(ChannelSession channelSession) {
        return (LoginProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), ProcessPurpose.LOGIN)
                .orElseGet(() -> sessionManagementService.createLoginProcessSession(
                        channelSession.getChannelSessionId(), Duration.ofMinutes(15)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPendingData(OrchestratorAttempt attempt) {
        String result = attempt.getResult();
        if (result == null || result.isBlank()) return new HashMap<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(result, MAP_TYPE);
            Object pending = parsed.get("pending");
            if (pending instanceof Map<?, ?> map) return new HashMap<>((Map<String, Object>) map);
        } catch (Exception ignored) {}
        return new HashMap<>();
    }

    private String toPendingJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(Map.of("pending", data));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pending data", e);
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(value.toString()); } catch (Exception e) { return null; }
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
