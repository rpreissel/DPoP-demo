package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsChallengeResult;
import com.example.dpop.auth_sms.AuthSmsEnrollResult;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.AuthenticationAttempt;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.ChannelState;
import com.example.dpop.orchestrator.session.LoginProcessSession;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessSession;
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
public class SmsAuthenticationService {

    private final SessionManagementService sessionManagementService;
    private final AccountService accountService;
    private final AuthSmsService authSmsService;

    public SmsAuthenticationService(
            SessionManagementService sessionManagementService,
            AccountService accountService,
            AuthSmsService authSmsService
    ) {
        this.sessionManagementService = sessionManagementService;
        this.accountService = accountService;
        this.authSmsService = authSmsService;
    }

    public OrchestratorResponse startAuthentication(String bindingKeyRef, String mode, Map<String, Object> data) {
        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));

        String phoneNumber = data != null ? (String) data.get("phoneNumber") : null;

        if ("use".equals(mode) && phoneNumber == null) {
            Long accountId = channelSession.getAccountId();
            if (accountId != null) {
                Long enrollmentId = accountService.findActiveSmsEnrollmentId(accountId).orElse(null);
                if (enrollmentId != null) {
                    AuthSmsChallengeResult challenge = authSmsService.sendChallenge(enrollmentId);

                    LoginProcessSession processSession = (LoginProcessSession) sessionManagementService
                            .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                            .orElseGet(() -> sessionManagementService.createLoginProcessSession(
                                    channelSession.getChannelSessionId(), Duration.ofMinutes(15)));

                    AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                            processSession.getProcessSessionId(), "sms", mode, Duration.ofMinutes(10));
                    attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
                    attempt.setMissingFields(serializeList(Arrays.asList("tan")));
                    attempt.setResult("{ \"enrollmentId\": " + enrollmentId + " }");
                    sessionManagementService.updateAttempt(attempt);

                    return new OrchestratorResponse(
                            channelSession.getChannelSessionId(), null,
                            new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                                    "INPUT_REQUIRED", Arrays.asList("tan"), Map.of("enrollmentId", enrollmentId)),
                            new OrchestratorResponse.NextRouting("authentication", "tanInput"),
                            new OrchestratorResponse.DemoHints(challenge.tan(),
                                    "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
                    );
                }
            }
        }

        if (phoneNumber == null || phoneNumber.isBlank()) {
            LoginProcessSession processSession = (LoginProcessSession) sessionManagementService
                    .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                    .orElseGet(() -> sessionManagementService.createLoginProcessSession(
                            channelSession.getChannelSessionId(), Duration.ofMinutes(15)));

            AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                    processSession.getProcessSessionId(), "sms", mode, Duration.ofMinutes(10));
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(Arrays.asList("phoneNumber")));
            sessionManagementService.updateAttempt(attempt);

            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(), null,
                    new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                            "INPUT_REQUIRED", Arrays.asList("phoneNumber"), null),
                    new OrchestratorResponse.NextRouting("enroll".equals(mode) ? "enrollment" : "authentication",
                            "setup", Arrays.asList("sms"))
            );
        }

        AuthSmsEnrollResult smsResult = authSmsService.enrollSms(phoneNumber);

        LoginProcessSession processSession = (LoginProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), null)
                .orElseGet(() -> sessionManagementService.createLoginProcessSession(
                        channelSession.getChannelSessionId(), Duration.ofMinutes(15)));

        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(), "sms", mode, Duration.ofMinutes(10));
        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(Arrays.asList("tan")));
        attempt.setResult("{ \"enrollmentId\": " + smsResult.enrollmentId() + " }");
        sessionManagementService.updateAttempt(attempt);

        String nextContext = "enroll".equals(mode) ? "enrollment" : "authentication";
        return new OrchestratorResponse(
                channelSession.getChannelSessionId(), null,
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                        "INPUT_REQUIRED", Arrays.asList("tan"), Map.of("enrollmentId", smsResult.enrollmentId())),
                new OrchestratorResponse.NextRouting(nextContext, "tanInput"),
                new OrchestratorResponse.DemoHints(smsResult.tan(),
                        "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
        );
    }

    public OrchestratorResponse submitAuthentication(UUID attemptId, String bindingKeyRef, String mode, Map<String, Object> data) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Attempt not found"));

        String tan = data != null ? (String) data.get("tan") : null;
        if (tan == null || tan.isBlank()) {
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "authentication", "INPUT_REQUIRED",
                            Arrays.asList("tan"), null),
                    new OrchestratorResponse.NextRouting("enroll".equals(mode) ? "enrollment" : "authentication", "tanInput"));
        }

        String resultJson = attempt.getResult();
        Long enrollmentId = null;
        if (resultJson != null) {
            try {
                enrollmentId = Long.parseLong(resultJson.replaceAll(".*\"enrollmentId\":\\s*(\\d+).*", "$1"));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot extract enrollmentId from attempt result");
            }
        }
        if (enrollmentId == null) {
            throw new IllegalStateException("No enrollmentId stored in attempt");
        }

        authSmsService.validateTan(enrollmentId, tan);

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> new IllegalArgumentException("Channel session not found"));
        Long accountId = channelSession.getAccountId();
        if (accountId != null && "enroll".equals(mode)) {
            accountService.addAuthenticationMethod(accountId, "sms", true,
                    Map.of("enrollmentId", enrollmentId));
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

    public OrchestratorResponse getAuthenticationStatus(UUID attemptId, String bindingKeyRef, String mode) {
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
