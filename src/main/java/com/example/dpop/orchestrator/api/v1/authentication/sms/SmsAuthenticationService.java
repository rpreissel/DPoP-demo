package com.example.dpop.orchestrator.api.v1.authentication.sms;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsChallengeResult;
import com.example.dpop.auth_sms.AuthSmsEnrollResult;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.auth_sms.EnrollmentRef;
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class SmsAuthenticationService {

    private final SessionManagementService sessionManagementService;
    private final AccountService accountService;
    private final AuthSmsService authSmsService;
    private final AttemptPendingStore pendingStore;

    public SmsAuthenticationService(
            SessionManagementService sessionManagementService,
            AccountService accountService,
            AuthSmsService authSmsService,
            AttemptPendingStore pendingStore
    ) {
        this.sessionManagementService = sessionManagementService;
        this.accountService = accountService;
        this.authSmsService = authSmsService;
        this.pendingStore = pendingStore;
    }

    public OrchestratorResponse startEnroll(UUID channelSessionId, String bindingKeyRef) {
        ChannelSession channelSession = getChannelSession(bindingKeyRef, channelSessionId);
        LoginProcessSession processSession = getOrCreateLoginSession(channelSession);
        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(), "sms", "enroll", Duration.ofMinutes(10));
        return advanceEnroll(attempt, SmsEnrollPending.empty(), channelSession);
    }

    public OrchestratorResponse submitEnroll(UUID attemptId, String bindingKeyRef, Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));
        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }
        ChannelSession channelSession = getChannelSession(bindingKeyRef, null);
        SmsEnrollPending pending = pendingStore.load(attempt, SmsEnrollPending.class);
        if (pending == null) pending = SmsEnrollPending.empty();
        pending = pending.merge(patchData);
        return advanceEnroll(attempt, pending, channelSession);
    }

    public OrchestratorResponse startUse(UUID channelSessionId, String bindingKeyRef) {
        ChannelSession channelSession = getChannelSession(bindingKeyRef, channelSessionId);
        Long accountId = channelSession.getAccountId();
        if (accountId == null) throw OrchestratorException.forbidden("No account bound to channel");

        Long enrollmentId = accountService.findActiveSmsEnrollmentId(accountId)
                .orElseThrow(() -> new IllegalStateException("No active SMS enrollment found"));
        EnrollmentRef ref = new EnrollmentRef(enrollmentId);

        AuthSmsChallengeResult challenge = authSmsService.startChallenge(ref);

        LoginProcessSession processSession = getOrCreateLoginSession(channelSession);
        AuthenticationAttempt attempt = sessionManagementService.createAuthenticationAttempt(
                processSession.getProcessSessionId(), "sms", "use", Duration.ofMinutes(10));

        SmsUsePending pending = new SmsUsePending(ref, null);
        pendingStore.save(attempt, pending);
        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        saveMissingFields(attempt, List.of("tan"));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(), null,
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                        "INPUT_REQUIRED", List.of("tan"), Map.of("enrollmentId", ref.id())),
                new OrchestratorResponse.NextRouting("sms", "use"),
                new OrchestratorResponse.DemoHints(challenge.tan(),
                        "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
        );
    }

    public OrchestratorResponse submitUse(UUID attemptId, String bindingKeyRef, Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));
        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }
        ChannelSession channelSession = getChannelSession(bindingKeyRef, null);
        SmsUsePending pending = pendingStore.load(attempt, SmsUsePending.class);
        if (pending == null) throw new IllegalStateException("No pending use data found");
        pending = pending.merge(patchData);
        return advanceUse(attempt, pending, channelSession);
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

    private EnrollStep nextEnrollStep(SmsEnrollPending p) {
        if (p.phoneNumber() == null || p.phoneNumber().isBlank())
            return new EnrollStep.NeedInput(p.missingUserInputs());
        if (p.enrollmentRef() == null)
            return new EnrollStep.StartEnrollment(p.phoneNumber());
        if (p.tan() == null || p.tan().isBlank())
            return new EnrollStep.NeedInput(List.of("tan"));
        if (!p.tanVerified())
            return new EnrollStep.ConfirmEnrollment(p.enrollmentRef(), p.tan());
        if (!p.enrollmentConfirmed())
            return new EnrollStep.ActivateMethod(p.enrollmentRef());
        throw new IllegalStateException("Enroll bereits abgeschlossen");
    }

    private OrchestratorResponse advanceEnroll(OrchestratorAttempt attempt,
                                               SmsEnrollPending pending,
                                               ChannelSession channel) {
        return switch (nextEnrollStep(pending)) {
            case EnrollStep.NeedInput(var missing) -> {
                pendingStore.save(attempt, pending);
                saveMissingFields(attempt, missing);
                attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
                sessionManagementService.updateAttempt(attempt);
                OrchestratorResponse.DemoHints hints = null;
                if (missing.contains("tan") && pending.enrollmentRef() != null) {
                    hints = new OrchestratorResponse.DemoHints(pending.tan(),
                            "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response");
                }
                yield new OrchestratorResponse(
                        channel.getChannelSessionId(), null,
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                                "INPUT_REQUIRED", missing, null),
                        new OrchestratorResponse.NextRouting("sms", "enroll"),
                        hints
                );
            }
            case EnrollStep.StartEnrollment(var phone) -> {
                AuthSmsEnrollResult result = authSmsService.startEnrollment(phone);
                SmsEnrollPending next = pending.withEnrollmentRef(result.enrollmentRef())
                        .merge(Map.of("tan", ""));
                SmsEnrollPending withRef = new SmsEnrollPending(
                        next.phoneNumber(), result.enrollmentRef(), null, false, false);
                pendingStore.save(attempt, withRef);
                saveMissingFields(attempt, List.of("tan"));
                attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
                sessionManagementService.updateAttempt(attempt);
                yield new OrchestratorResponse(
                        channel.getChannelSessionId(), null,
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                                "INPUT_REQUIRED", List.of("tan"), null),
                        new OrchestratorResponse.NextRouting("sms", "enroll"),
                        new OrchestratorResponse.DemoHints(result.tan(),
                                "DEMO ONLY – in production the TAN is sent via SMS and never included in the API response")
                );
            }
            case EnrollStep.ConfirmEnrollment(var ref, var tan) -> {
                try {
                    authSmsService.confirmEnrollment(ref, tan);
                } catch (Exception e) {
                    throw OrchestratorException.verificationFailed("TAN validation failed");
                }
                yield advanceEnroll(attempt, pending.withTanVerified(), channel);
            }
            case EnrollStep.ActivateMethod(var ref) -> {
                accountService.addAuthenticationMethod(
                        channel.getAccountId(), "sms", true, Map.of("enrollmentId", ref.id()));
                attempt.setStatus(AttemptStatus.VERIFIED);
                attempt.setResult("{ \"verified\": true }");
                sessionManagementService.updateAttempt(attempt);
                sessionManagementService.updateChannelState(channel.getChannelSessionId(), ChannelState.AUTHENTICATED);
                ProcessSession processSession = sessionManagementService
                        .getProcessSessionById(attempt.getProcessSessionId()).orElse(null);
                Long personId = processSession != null ? processSession.getPersonId() : null;
                yield new OrchestratorResponse(
                        channel.getChannelSessionId(),
                        new OrchestratorResponse.ProcessState("REGISTRATION", "COMPLETED", personId, channel.getAccountId()),
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication", "VERIFIED",
                                null, Map.of("verified", true)),
                        new OrchestratorResponse.NextRouting("authentication", "authenticated", null,
                                null, channel.getAccountId(), personId)
                );
            }
        };
    }

    private UseStep nextUseStep(SmsUsePending p) {
        if (p.tan() == null || p.tan().isBlank())
            return new UseStep.NeedInput(p.missingUserInputs());
        return new UseStep.VerifyChallenge(p.enrollmentRef(), p.tan());
    }

    private OrchestratorResponse advanceUse(OrchestratorAttempt attempt,
                                            SmsUsePending pending,
                                            ChannelSession channel) {
        return switch (nextUseStep(pending)) {
            case UseStep.NeedInput(var missing) -> {
                pendingStore.save(attempt, pending);
                saveMissingFields(attempt, missing);
                attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
                sessionManagementService.updateAttempt(attempt);
                yield new OrchestratorResponse(
                        channel.getChannelSessionId(), null,
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication",
                                "INPUT_REQUIRED", missing,
                                pending.enrollmentRef() != null ? Map.of("enrollmentId", pending.enrollmentRef().id()) : null),
                        new OrchestratorResponse.NextRouting("sms", "use")
                );
            }
            case UseStep.VerifyChallenge(var ref, var tan) -> {
                try {
                    authSmsService.verifyChallenge(ref, tan);
                } catch (Exception e) {
                    throw OrchestratorException.verificationFailed("TAN validation failed");
                }
                attempt.setStatus(AttemptStatus.VERIFIED);
                attempt.setResult("{ \"verified\": true }");
                sessionManagementService.updateAttempt(attempt);
                sessionManagementService.updateChannelState(channel.getChannelSessionId(), ChannelState.AUTHENTICATED);
                ProcessSession processSession = sessionManagementService
                        .getProcessSessionById(attempt.getProcessSessionId()).orElse(null);
                Long personId = processSession != null ? processSession.getPersonId() : null;
                yield new OrchestratorResponse(
                        channel.getChannelSessionId(),
                        new OrchestratorResponse.ProcessState("LOGIN", "COMPLETED", personId, channel.getAccountId()),
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "authentication", "VERIFIED",
                                null, Map.of("verified", true)),
                        new OrchestratorResponse.NextRouting("authentication", "authenticated", null,
                                null, channel.getAccountId(), personId)
                );
            }
        };
    }

    private void saveMissingFields(OrchestratorAttempt attempt, List<String> missing) {
        if (missing.isEmpty()) {
            attempt.setMissingFields(null);
        } else {
            attempt.setMissingFields(missing.stream()
                    .map(s -> "\"" + s + "\"")
                    .collect(Collectors.joining(", ", "[", "]")));
        }
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
}
