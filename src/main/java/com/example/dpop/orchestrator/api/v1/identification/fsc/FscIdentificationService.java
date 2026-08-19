package com.example.dpop.orchestrator.api.v1.identification.fsc;

import com.example.dpop.account.AccountProfile;
import com.example.dpop.account.AccountService;
import com.example.dpop.ext_stammdaten.ExtStammdatenService;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
import com.example.dpop.orchestrator.api.v1.AttemptPendingStore;
import com.example.dpop.orchestrator.api.v1.OrchestratorException;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.ChannelSession;
import com.example.dpop.orchestrator.session.IdentificationAttempt;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessPurpose;
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
import java.util.stream.Collectors;

@Service
@Transactional
public class FscIdentificationService {

    private final SessionManagementService sessionManagementService;
    private final ExtStammdatenService extStammdatenService;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;
    private final AttemptPendingStore pendingStore;

    public FscIdentificationService(
            SessionManagementService sessionManagementService,
            ExtStammdatenService extStammdatenService,
            IdFscService idFscService,
            AccountService accountService,
            AccountBindingKeyMappingService accountBindingKeyMappingService,
            AttemptPendingStore pendingStore
    ) {
        this.sessionManagementService = sessionManagementService;
        this.extStammdatenService = extStammdatenService;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
        this.pendingStore = pendingStore;
    }

    public OrchestratorResponse startIdentification(UUID channelSessionId, String bindingKeyRef,
            Map<String, Object> data) {
        ChannelSession channelSession = getChannelSession(bindingKeyRef, channelSessionId);

        RegistrationProcessSession processSession = (RegistrationProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), ProcessPurpose.REGISTRATION)
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));
        processSession.setSelectedIdentificationMethod("fsc");
        sessionManagementService.updateProcessSession(processSession);

        IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                processSession.getProcessSessionId(), "fsc", "input", Duration.ofMinutes(10));

        FscPending pending = FscPending.empty().merge(data);
        return advance(attempt, pending, bindingKeyRef, channelSession, processSession);
    }

    public OrchestratorResponse submitIdentificationData(UUID attemptId, String bindingKeyRef,
            Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }

        FscPending stored = pendingStore.load(attempt, FscPending.class);
        FscPending pending = (stored != null ? stored : FscPending.empty()).merge(patchData);

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> OrchestratorException.forbidden("Channel session not found"));
        ProcessSession processSession = sessionManagementService
                .getProcessSessionById(attempt.getProcessSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        return advance(attempt, pending, bindingKeyRef, channelSession, processSession);
    }

    public OrchestratorResponse getIdentificationStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        Object result = attempt.getStatus() == AttemptStatus.VERIFIED ? attempt.getResult() : null;
        return new OrchestratorResponse(null, null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(), "identification",
                        attempt.getStatus().toString(), null, result),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    private OrchestratorResponse advance(OrchestratorAttempt attempt, FscPending pending,
            String bindingKeyRef, ChannelSession channelSession, ProcessSession processSession) {
        return switch (nextStep(pending)) {
            case FscStep.NeedInput(var missing) -> {
                pendingStore.save(attempt, pending);
                attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
                saveMissingFields(attempt, missing);
                sessionManagementService.updateAttempt(attempt);
                yield new OrchestratorResponse(
                        channelSession.getChannelSessionId(),
                        new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                        new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "identification",
                                "INPUT_REQUIRED", missing, null),
                        new OrchestratorResponse.NextRouting("fsc", "input")
                );
            }
            case FscStep.Verify(var kvnr, var fsc) ->
                verifyFsc(attempt, kvnr, fsc, bindingKeyRef, channelSession, processSession);
        };
    }

    private FscStep nextStep(FscPending p) {
        List<String> missing = p.missingFields();
        return missing.isEmpty()
                ? new FscStep.Verify(p.kvnr(), p.fsc())
                : new FscStep.NeedInput(missing);
    }

    private OrchestratorResponse verifyFsc(OrchestratorAttempt attempt, String kvnr, String fsc,
            String bindingKeyRef, ChannelSession channelSession, ProcessSession processSession) {
        Long personId = extStammdatenService.findPersonIdByKvnr(kvnr).orElse(null);
        if (personId == null || !idFscService.validateFsc(personId, fsc)) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setResult("{}");
            sessionManagementService.updateAttempt(attempt);
            throw OrchestratorException.verificationFailed("FSC validation failed");
        }

        attempt.setStatus(AttemptStatus.VERIFIED);
        attempt.setResult("{ \"identified\": true, \"personId\": " + personId + " }");
        sessionManagementService.updateAttempt(attempt);

        processSession.setPersonId(personId);
        sessionManagementService.updateProcessSession(processSession);

        AccountProfile account = accountService.identifyAccount(
                personId, "fsc", "HIGH", processSession.getProcessSessionId(), Map.of("kvnr", kvnr));
        sessionManagementService.setAccountId(channelSession.getChannelSessionId(), account.accountId());
        accountBindingKeyMappingService.mapBindingKeyToAccount(bindingKeyRef, account.accountId());

        boolean hasAuth = !account.activeAuthenticationMethods().isEmpty();
        OrchestratorResponse.NextRouting next = hasAuth
                ? new OrchestratorResponse.NextRouting("authentication", "selectMethod",
                        account.activeAuthenticationMethods(), null, account.accountId(), personId)
                : new OrchestratorResponse.NextRouting("enrollment", "selectMethod",
                        Arrays.asList("sms"), null, account.accountId(), personId);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId()),
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "identification", "VERIFIED",
                        null, Map.of("identified", true, "personId", personId)),
                next
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

    private void saveMissingFields(OrchestratorAttempt attempt, List<String> missing) {
        attempt.setMissingFields(missing.stream()
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", ", "[", "]")));
    }
}
