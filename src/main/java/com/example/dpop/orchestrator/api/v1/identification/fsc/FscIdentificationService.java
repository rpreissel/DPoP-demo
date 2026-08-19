package com.example.dpop.orchestrator.api.v1.identification.fsc;

import com.example.dpop.account.AccountProfile;
import com.example.dpop.account.AccountService;
import com.example.dpop.ext_stammdaten.ExtStammdatenService;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
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
import tools.jackson.databind.ObjectMapper;
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
public class FscIdentificationService {

    private static final tools.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new tools.jackson.core.type.TypeReference<>() {};

    private final SessionManagementService sessionManagementService;
    private final ExtStammdatenService extStammdatenService;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;
    private final ObjectMapper objectMapper;

    public FscIdentificationService(
            SessionManagementService sessionManagementService,
            ExtStammdatenService extStammdatenService,
            IdFscService idFscService,
            AccountService accountService,
            AccountBindingKeyMappingService accountBindingKeyMappingService,
            ObjectMapper objectMapper
    ) {
        this.sessionManagementService = sessionManagementService;
        this.extStammdatenService = extStammdatenService;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
        this.objectMapper = objectMapper;
    }

    public OrchestratorResponse startIdentification(UUID channelSessionId, String bindingKeyRef, Map<String, Object> data) {
        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> OrchestratorException.forbidden("Channel session not found"));
        ensureChannelMatches(channelSessionId, channelSession);

        RegistrationProcessSession processSession = (RegistrationProcessSession) sessionManagementService
                .getLatestProcessSessionByChannel(channelSession.getChannelSessionId(), ProcessPurpose.REGISTRATION)
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        processSession.setSelectedIdentificationMethod("fsc");
        sessionManagementService.updateProcessSession(processSession);

        IdentificationAttempt attempt = sessionManagementService.createIdentificationAttempt(
                processSession.getProcessSessionId(), "fsc", "input", Duration.ofMinutes(10));

        Map<String, Object> pending = data != null ? new HashMap<>(data) : new HashMap<>();
        List<String> missingFields = computeMissingFscFields(pending);

        if (missingFields.isEmpty()) {
            return verifyFsc(attempt, pending, bindingKeyRef, channelSession, processSession);
        }

        attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
        attempt.setMissingFields(serializeList(missingFields));
        attempt.setResult(toPendingJson(pending));
        sessionManagementService.updateAttempt(attempt);

        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", null, null),
                new OrchestratorResponse.AttemptState(attempt.getAttemptId(), "identification", "INPUT_REQUIRED", missingFields, null),
                new OrchestratorResponse.NextRouting("fsc", "input")
        );
    }

    private void ensureChannelMatches(UUID expectedChannelSessionId, ChannelSession actualChannelSession) {
        if (expectedChannelSessionId == null) return;
        if (!actualChannelSession.getChannelSessionId().equals(expectedChannelSessionId)) {
            throw OrchestratorException.forbidden("Channel session mismatch");
        }
    }

    public OrchestratorResponse submitIdentificationData(UUID attemptId, String bindingKeyRef, Map<String, Object> patchData) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        if (attempt.getStatus() == AttemptStatus.VERIFIED) {
            throw OrchestratorException.conflict("Attempt is already verified");
        }

        Map<String, Object> pending = loadPendingData(attempt);
        if (patchData != null) pending.putAll(patchData);

        List<String> missingFields = computeMissingFscFields(pending);

        if (!missingFields.isEmpty()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(missingFields));
            attempt.setResult(toPendingJson(pending));
            sessionManagementService.updateAttempt(attempt);
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "identification", "INPUT_REQUIRED", missingFields, null),
                    new OrchestratorResponse.NextRouting("fsc", "input"));
        }

        ChannelSession channelSession = sessionManagementService.getChannelSessionByBindingKeyRef(bindingKeyRef)
                .orElseThrow(() -> OrchestratorException.forbidden("Channel session not found"));
        ProcessSession processSession = sessionManagementService.getProcessSessionById(attempt.getProcessSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));

        return verifyFsc(attempt, pending, bindingKeyRef, channelSession, processSession);
    }

    private OrchestratorResponse verifyFsc(OrchestratorAttempt attempt, Map<String, Object> data,
            String bindingKeyRef, ChannelSession channelSession, ProcessSession processSession) {
        UUID attemptId = attempt.getAttemptId();
        String kvnr = (String) data.get("kvnr");
        String fsc = (String) data.get("fsc");

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
                personId, "fsc", "HIGH",
                processSession.getProcessSessionId(),
                Map.of("kvnr", kvnr)
        );
        sessionManagementService.setAccountId(channelSession.getChannelSessionId(), account.accountId());
        accountBindingKeyMappingService.mapBindingKeyToAccount(bindingKeyRef, account.accountId());

        boolean hasAuth = !account.activeAuthenticationMethods().isEmpty();
        if (hasAuth) {
            return new OrchestratorResponse(
                    channelSession.getChannelSessionId(),
                    new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId()),
                    new OrchestratorResponse.AttemptState(attemptId, "identification", "VERIFIED", null,
                            Map.of("identified", true, "personId", personId)),
                    new OrchestratorResponse.NextRouting("authentication", "selectMethod",
                            account.activeAuthenticationMethods(), null, account.accountId(), personId)
            );
        }
        return new OrchestratorResponse(
                channelSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId()),
                new OrchestratorResponse.AttemptState(attemptId, "identification", "VERIFIED", null,
                        Map.of("identified", true, "personId", personId)),
                new OrchestratorResponse.NextRouting("enrollment", "selectMethod", Arrays.asList("sms"),
                        null, account.accountId(), personId)
        );
    }

    public OrchestratorResponse getIdentificationStatus(UUID attemptId, String bindingKeyRef) {
        OrchestratorAttempt attempt = sessionManagementService.getAttemptById(attemptId)
                .orElseThrow(() -> OrchestratorException.notFound("Attempt not found"));

        Object result = null;
        if (attempt.getStatus() == AttemptStatus.VERIFIED && attempt.getResult() != null) {
            result = attempt.getResult();
        }

        return new OrchestratorResponse(null, null,
                new OrchestratorResponse.AttemptState(
                        attempt.getAttemptId(), "identification", attempt.getStatus().toString(), null, result),
                new OrchestratorResponse.NextRouting(attempt.getNextContext(), attempt.getNextStep())
        );
    }

    private List<String> computeMissingFscFields(Map<String, Object> data) {
        List<String> missing = new ArrayList<>();
        if (!data.containsKey("kvnr") || data.get("kvnr") == null) missing.add("kvnr");
        if (!data.containsKey("fsc") || data.get("fsc") == null) missing.add("fsc");
        return missing;
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
