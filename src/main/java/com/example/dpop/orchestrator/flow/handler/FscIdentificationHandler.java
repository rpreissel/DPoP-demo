package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.account.AccountProfile;
import com.example.dpop.account.AccountService;
import com.example.dpop.ext_stammdaten.ExtStammdatenService;
import com.example.dpop.ext_stammdaten.PersonData;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.account.AccountBindingKeyMappingService;
import com.example.dpop.orchestrator.flow.CommandKey;
import com.example.dpop.orchestrator.flow.CommandPolicy;
import com.example.dpop.orchestrator.flow.CommandRegistration;
import com.example.dpop.orchestrator.flow.CommandRegistry;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.flow.command.FscStartCommand;
import com.example.dpop.orchestrator.flow.command.FscSubmitCommand;
import com.example.dpop.orchestrator.session.AuthenticationMethodProvider;
import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Component
public class FscIdentificationHandler {

    private final ExtStammdatenService extStammdatenService;
    private final IdFscService idFscService;
    private final AccountService accountService;
    private final AccountBindingKeyMappingService accountBindingKeyMappingService;
    private final AuthenticationMethodProvider authenticationMethodProvider;
    private final CommandRegistry commandRegistry;

    public FscIdentificationHandler(ExtStammdatenService extStammdatenService,
                                    IdFscService idFscService,
                                    AccountService accountService,
                                    AccountBindingKeyMappingService accountBindingKeyMappingService,
                                    AuthenticationMethodProvider authenticationMethodProvider,
                                    CommandRegistry commandRegistry) {
        this.extStammdatenService = extStammdatenService;
        this.idFscService = idFscService;
        this.accountService = accountService;
        this.accountBindingKeyMappingService = accountBindingKeyMappingService;
        this.authenticationMethodProvider = authenticationMethodProvider;
        this.commandRegistry = commandRegistry;
    }

    public String method() {
        return "fsc";
    }

    @PostConstruct
    void registerCommands() {
        commandRegistry.register(
                new CommandKey("fsc", "start"),
                new CommandRegistration<>(
                        FscStartCommand.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        this::start
                )
        );
        commandRegistry.register(
                new CommandKey("fsc", "submit"),
                new CommandRegistration<>(
                        FscSubmitCommand.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        this::submit
                )
        );
    }

    public NextStep start(BindingSession session, FscStartCommand request) {
        String kvnr = request == null ? null : request.kvnr();
        String name = request == null ? null : request.name();
        String vorname = request == null ? null : request.vorname();

        PersonData person = extStammdatenService.findPersonByKvnr(kvnr)
                .orElseThrow(() -> new FlowSessionException("Person with given KVNR not found"));

        if (!person.name().equals(name) || !person.vorname().equals(vorname)) {
            throw new FlowSessionException("Person data does not match");
        }

        session.setPersonId(person.id());
        session.setSelectedIdentificationMethod("fsc");
        return new NextStep.FscInputNextStep();
    }

    public NextStep submit(BindingSession session, FscSubmitCommand request) {
        String fscCode = request == null ? null : request.fsc();
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

        PersonData person = extStammdatenService.findPersonById(personId)
                .orElseThrow(() -> new FlowSessionException("Person not found"));

        AccountProfile account = accountService.identifyAccount(
                personId,
                "fsc",
                "HIGH",
                sessionId,
                Map.of("kvnr", person.kvnr())
        );
        session.setAccountId(account.accountId());
        accountBindingKeyMappingService.mapBindingKeyToAccount(session.getBindingKeyRef(), account.accountId());

        if (!account.activeAuthenticationMethods().isEmpty()) {
            return new NextStep.AuthenticationMethodSelectionNextStep(account.activeAuthenticationMethods());
        }
        return new NextStep.AuthenticationSetupNextStep(authenticationMethodProvider.availableMethods());
    }
}
