package com.example.dpop.orchestrator.flow;

import com.example.dpop.account.Account;
import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.auth_sms.AuthSmsSetup;
import com.example.dpop.auth_sms.AuthSmsSetupResult;
import com.example.dpop.ext_stammdaten.Person;
import com.example.dpop.ext_stammdaten.PersonRepository;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountJwkMappingService;
import com.example.dpop.orchestrator.session.AuthenticationMethodProvider;
import com.example.dpop.orchestrator.session.ClientFlowSessionService;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.FlowNextStepResolver;
import com.example.dpop.orchestrator.session.IdentificationMethodProvider;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class FlowActionService {

    private final ClientFlowSessionService flowSessionService;
    private final PersonRepository personRepository;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountJwkMappingService accountJwkMappingService;
    private final AuthSmsService authSmsService;
    private final IdentificationMethodProvider identificationMethodProvider;
    private final AuthenticationMethodProvider authenticationMethodProvider;
    private final FlowNextStepResolver nextStepResolver;

    public FlowActionService(ClientFlowSessionService flowSessionService,
                             PersonRepository personRepository,
                             IdFscService idFscService,
                             AccountService accountService,
                             AccountJwkMappingService accountJwkMappingService,
                             AuthSmsService authSmsService,
                             IdentificationMethodProvider identificationMethodProvider,
                             AuthenticationMethodProvider authenticationMethodProvider,
                             FlowNextStepResolver nextStepResolver) {
        this.flowSessionService = flowSessionService;
        this.personRepository = personRepository;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountJwkMappingService = accountJwkMappingService;
        this.authSmsService = authSmsService;
        this.identificationMethodProvider = identificationMethodProvider;
        this.authenticationMethodProvider = authenticationMethodProvider;
        this.nextStepResolver = nextStepResolver;
    }

    public FlowSetupResponse createFlow(String thumbprint) {
        ClientSession session = resolveOrRotateSession(thumbprint);
        return new FlowSetupResponse(session.getSessionId(), nextStepResolver.resolve(session));
    }

    private ClientSession resolveOrRotateSession(String thumbprint) {
        Optional<Long> knownAccountId = accountJwkMappingService.findAccountIdByJwkThumbprint(thumbprint);
        Optional<ClientSession> existingSession = flowSessionService.findByJwkThumbprint(thumbprint);

        if (existingSession.isPresent()) {
            ClientSession session = existingSession.get();
            Long accountId = session.getAccountId();
            boolean accountHasActiveMethod = accountId != null
                    && accountService.hasActiveAuthenticationMethod(accountId);

            if (accountHasActiveMethod || knownAccountId.filter(accountService::hasActiveAuthenticationMethod).isPresent()) {
                session = flowSessionService.rotateSessionId(thumbprint);
                Long effectiveAccountId = accountId != null ? accountId : knownAccountId.orElseThrow();
                session.setAccountId(effectiveAccountId);
                return flowSessionService.save(session);
            }

            return session;
        }

        if (knownAccountId.isPresent() && accountService.hasActiveAuthenticationMethod(knownAccountId.get())) {
            ClientSession session = flowSessionService.getOrCreateByJwkThumbprint(thumbprint);
            session.setAccountId(knownAccountId.get());
            return flowSessionService.save(session);
        }

        return flowSessionService.getOrCreateByJwkThumbprint(thumbprint);
    }

    public FlowSetupResponse startIdentification(UUID sessionId, String thumbprint, String method, FscIdentificationRequest request) {
        if (!"fsc".equals(method)) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }

        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);

        Person person = personRepository.findByKvnr(request.kvnr())
                .orElseThrow(() -> new FlowSessionException("Person with given KVNR not found"));

        if (!person.getName().equals(request.name()) || !person.getVorname().equals(request.vorname())) {
            throw new FlowSessionException("Person data does not match");
        }

        session.setPersonId(person.getId());
        session.setSelectedIdentificationMethod("fsc");
        flowSessionService.save(session);

        return toResponse(session);
    }

    public FlowSetupResponse submitIdentification(UUID sessionId, String thumbprint, String method, FscInputRequest request) {
        if (!"fsc".equals(method)) {
            throw new IllegalArgumentException("Unsupported identification method: " + method);
        }

        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);

        Long personId = session.getPersonId();
        if (personId == null) {
            throw new FlowSessionException("No person selected for this session");
        }

        String fscCode = request.fsc();
        if (fscCode == null || fscCode.isBlank()) {
            throw new FlowSessionException("FSC code is required");
        }

        if (!idFscService.validateFsc(personId, fscCode)) {
            throw new FlowSessionException("Invalid or expired FSC code");
        }

        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new FlowSessionException("Person not found"));

        Account account = accountService.identifyAccount(
                personId,
                "fsc",
                "HIGH",
                sessionId,
                Map.of("kvnr", person.getKvnr())
        );
        session.setAccountId(account.getId());
        accountJwkMappingService.mapJwkToAccount(thumbprint, account.getId());
        flowSessionService.save(session);

        return toResponse(session);
    }

    public FlowSetupResponse startAuthentication(UUID sessionId, String thumbprint, String method, SmsSetupRequest request) {
        if (!"sms".equals(method)) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }

        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        Long accountId = requireAccountId(session);

        String phoneNumber;
        boolean isChallenge = accountService.hasActiveAuthenticationMethod(accountId);
        if (isChallenge) {
            phoneNumber = accountService.findActiveSmsPhoneNumber(accountId)
                    .orElseThrow(() -> new FlowSessionException("No active sms authentication method configured"));
        } else {
            if (request == null || request.phoneNumber() == null || request.phoneNumber().isBlank()) {
                throw new FlowSessionException("phoneNumber is required for sms setup");
            }
            phoneNumber = request.phoneNumber();
        }

        AuthSmsSetupResult smsResult = authSmsService.setupSms(phoneNumber);

        session.setSelectedAuthenticationMethod("sms");
        session.setPendingChallenge(Map.of(
                "method", "sms",
                "challengeId", smsResult.smsSetupId(),
                "tan", smsResult.tan()
        ));
        flowSessionService.save(session);

        return toResponse(session);
    }

    public FlowSetupResponse verifyAuthentication(UUID sessionId, String thumbprint, String method, SmsTanRequest request) {
        if (!"sms".equals(method)) {
            throw new IllegalArgumentException("Unsupported authentication method: " + method);
        }

        ClientSession session = flowSessionService.requireSession(sessionId, thumbprint);
        Long accountId = requireAccountId(session);

        AuthSmsSetup validatedSetup = authSmsService.validateTan(request.smsSetupId(), request.tan());

        boolean wasSetup = !accountService.hasActiveAuthenticationMethod(accountId);
        if (wasSetup) {
            accountService.addAuthenticationMethod(
                    accountId,
                    "sms",
                    true,
                    Map.of("smsSetupId", validatedSetup.getId(), "phoneNumber", validatedSetup.getPhoneNumber())
            );
        }
        session.clearPendingChallenge();
        session.getData().remove("selectedAuthenticationMethod");
        session.setPhase("authenticated");
        flowSessionService.save(session);

        return toResponse(session);
    }

    public FlowSetupResponse verifyAuthentication(UUID sessionId, String thumbprint, String method, SmsVerifyRequest request) {
        return verifyAuthentication(sessionId, thumbprint, method, new SmsTanRequest(request.smsSetupId(), request.tan()));
    }

    private Long requireAccountId(ClientSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            throw new FlowSessionException("No account linked to this session");
        }
        return accountId;
    }

    private FlowSetupResponse toResponse(ClientSession session) {
        return new FlowSetupResponse(nextStepResolver.resolve(session));
    }
}
