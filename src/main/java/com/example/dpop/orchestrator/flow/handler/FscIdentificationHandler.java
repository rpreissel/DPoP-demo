package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.account.Account;
import com.example.dpop.account.AccountService;
import com.example.dpop.ext_stammdaten.Person;
import com.example.dpop.ext_stammdaten.PersonRepository;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.flow.IdentificationMethodHandler;
import com.example.dpop.orchestrator.session.AuthenticationMethodProvider;
import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class FscIdentificationHandler implements IdentificationMethodHandler {

    private final PersonRepository personRepository;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;
    private final AuthenticationMethodProvider authenticationMethodProvider;

    public FscIdentificationHandler(PersonRepository personRepository,
                                    IdFscService idFscService,
                                    AccountService accountService,
                                    AccountBindingKeyMappingService accountBindingKeyMappingService,
                                    AuthenticationMethodProvider authenticationMethodProvider) {
        this.personRepository = personRepository;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
        this.authenticationMethodProvider = authenticationMethodProvider;
    }

    @Override
    public String method() {
        return "fsc";
    }

    @Override
    public NextStep start(BindingSession session, Map<String, Object> request) {
        String kvnr = getString(request, "kvnr");
        String name = getString(request, "name");
        String vorname = getString(request, "vorname");

        Person person = personRepository.findByKvnr(kvnr)
                .orElseThrow(() -> new FlowSessionException("Person with given KVNR not found"));

        if (!person.getName().equals(name) || !person.getVorname().equals(vorname)) {
            throw new FlowSessionException("Person data does not match");
        }

        session.setPersonId(person.getId());
        session.setSelectedIdentificationMethod("fsc");
        return new NextStep.FscInputNextStep();
    }

    @Override
    public NextStep submit(BindingSession session, Map<String, Object> request) {
        String fscCode = getString(request, "fsc");
        UUID sessionId = session.getSessionId();

        Long personId = session.getPersonId();
        if (personId == null) {
            throw new FlowSessionException("No person selected for this session");
        }

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
        accountBindingKeyMappingService.mapBindingKeyToAccount(session.getBindingKeyRef(), account.getId());

        if (accountService.hasActiveAuthenticationMethod(account.getId())) {
            return new NextStep.AuthenticationMethodSelectionNextStep(authenticationMethodProvider.activeMethods(account));
        }
        return new NextStep.AuthenticationSetupNextStep(authenticationMethodProvider.availableMethods());
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
