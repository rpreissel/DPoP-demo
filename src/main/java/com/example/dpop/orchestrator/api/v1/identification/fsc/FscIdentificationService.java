package com.example.dpop.orchestrator.api.v1.identification.fsc;

import com.example.dpop.account.AccountProfile;
import com.example.dpop.account.AccountService;
import com.example.dpop.ext_stammdaten.ExtStammdatenService;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse;
import com.example.dpop.orchestrator.session.AttemptStatus;
import com.example.dpop.orchestrator.session.IdentificationAttempt;
import com.example.dpop.orchestrator.session.OrchestratorAttempt;
import com.example.dpop.orchestrator.session.ProcessSession;
import com.example.dpop.orchestrator.session.RegistrationProcessSession;
import com.example.dpop.orchestrator.session.SessionManagementService;
import com.example.dpop.orchestrator.session.ChannelSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class FscIdentificationService {

    private final SessionManagementService sessionManagementService;
    private final ExtStammdatenService extStammdatenService;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;

    public FscIdentificationService(
            SessionManagementService sessionManagementService,
            ExtStammdatenService extStammdatenService,
            IdFscService idFscService,
            AccountService accountService,
            AccountBindingKeyMappingService accountBindingKeyMappingService
    ) {
        this.sessionManagementService = sessionManagementService;
        this.extStammdatenService = extStammdatenService;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
    }

    public OrchestratorResponse startIdentification(String bindingKeyRef, Map<String, Object> data) {
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
        if (data == null || !data.containsKey("fsc")) missingFields.add("fsc");

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

        List<String> missingFields = new ArrayList<>();
        if (data == null || !data.containsKey("kvnr")) missingFields.add("kvnr");
        if (data == null || !data.containsKey("fsc")) missingFields.add("fsc");

        if (!missingFields.isEmpty()) {
            attempt.setStatus(AttemptStatus.INPUT_REQUIRED);
            attempt.setMissingFields(serializeList(missingFields));
            sessionManagementService.updateAttempt(attempt);
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "identification", "INPUT_REQUIRED", missingFields, null),
                    new OrchestratorResponse.NextRouting("fsc", "input"));
        }

        String kvnr = (String) data.get("kvnr");
        String fsc = (String) data.get("fsc");

        Long personId = extStammdatenService.findPersonIdByKvnr(kvnr).orElse(null);
        if (personId == null || !idFscService.validateFsc(personId, fsc)) {
            attempt.setStatus(AttemptStatus.FAILED);
            attempt.setMissingFields(null);
            attempt.setResult("{ \"error\": \"invalid_fsc\" }");
            sessionManagementService.updateAttempt(attempt);
            return new OrchestratorResponse(null, null,
                    new OrchestratorResponse.AttemptState(attemptId, "identification", "FAILED", null, Map.of("error", "invalid_fsc")),
                    new OrchestratorResponse.NextRouting("fsc", "input"));
        }

        attempt.setStatus(AttemptStatus.VERIFIED);
        attempt.setResult("{ \"identified\": true, \"personId\": " + personId + " }");
        sessionManagementService.updateAttempt(attempt);

        ProcessSession processSession = sessionManagementService.getProcessSessionById(attempt.getProcessSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Process session not found"));
        processSession.setPersonId(personId);
        sessionManagementService.updateProcessSession(processSession);

        AccountProfile account = accountService.identifyAccount(
                personId, "fsc", "HIGH",
                processSession.getProcessSessionId(),
                Map.of("kvnr", kvnr)
        );
        sessionManagementService.setAccountId(processSession.getChannelSessionId(), account.accountId());
        accountBindingKeyMappingService.mapBindingKeyToAccount(bindingKeyRef, account.accountId());

        boolean hasAuth = !account.activeAuthenticationMethods().isEmpty();
        if (hasAuth) {
            return new OrchestratorResponse(
                    processSession.getChannelSessionId(),
                    new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId()),
                    new OrchestratorResponse.AttemptState(attemptId, "identification", "VERIFIED", null,
                            Map.of("identified", true, "personId", personId)),
                    new OrchestratorResponse.NextRouting("authentication", "selectMethod",
                            account.activeAuthenticationMethods(), null, account.accountId(), personId)
            );
        }
        return new OrchestratorResponse(
                processSession.getChannelSessionId(),
                new OrchestratorResponse.ProcessState("REGISTRATION", "ACTIVE", personId, account.accountId()),
                new OrchestratorResponse.AttemptState(attemptId, "identification", "VERIFIED", null,
                        Map.of("identified", true, "personId", personId)),
                new OrchestratorResponse.NextRouting("enrollment", "selectMethod", Arrays.asList("sms"),
                        null, account.accountId(), personId)
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
