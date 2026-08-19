package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsChallengeResult;
import com.example.dpop.auth_sms.AuthSmsEnrollResult;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.orchestrator.flow.CommandKey;
import com.example.dpop.orchestrator.flow.CommandPolicy;
import com.example.dpop.orchestrator.flow.CommandRegistration;
import com.example.dpop.orchestrator.flow.CommandRegistry;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.flow.command.SmsStartCommand;
import com.example.dpop.orchestrator.flow.command.SmsVerifyCommand;
import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class SmsAuthenticationHandler {

    private final AuthSmsService authSmsService;
    private final AccountService accountService;
    private final CommandRegistry commandRegistry;

    public SmsAuthenticationHandler(AuthSmsService authSmsService,
                                    AccountService accountService,
                                    CommandRegistry commandRegistry) {
        this.authSmsService = authSmsService;
        this.accountService = accountService;
        this.commandRegistry = commandRegistry;
    }

    public String method() {
        return "sms";
    }

    @PostConstruct
    void registerCommands() {
        commandRegistry.register(
                new CommandKey("sms", "start"),
                new CommandRegistration<>(
                        SmsStartCommand.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        this::start
                )
        );
        commandRegistry.register(
                new CommandKey("sms", "verify"),
                new CommandRegistration<>(
                        SmsVerifyCommand.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), "authenticated"),
                        this::verify
                )
        );
    }

    public NextStep start(BindingSession session, SmsStartCommand request) {
        Long accountId = requireAccountId(session);
        boolean isChallenge = accountService.hasActiveAuthenticationMethod(accountId);

        AuthSmsEnrollResult smsResult;
        if (isChallenge) {
            Long enrollmentId = accountService.findActiveSmsEnrollmentId(accountId)
                    .orElseThrow(() -> new FlowSessionException("No active sms enrollment configured"));
            AuthSmsChallengeResult challenge = authSmsService.sendChallenge(enrollmentId);
            session.setSelectedAuthenticationMethod("sms");
            session.setPendingChallenge(Map.of(
                    "method", "sms",
                    "challengeId", challenge.enrollmentId(),
                    "tan", challenge.tan()
            ));
            return new NextStep.SmsTanInputNextStep(challenge.enrollmentId(), challenge.tan());
        } else {
            String phoneNumber = request == null ? null : request.phoneNumber();
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new FlowSessionException("phoneNumber is required for sms setup");
            }
            smsResult = authSmsService.enrollSms(phoneNumber);
        }

        session.setSelectedAuthenticationMethod("sms");
        session.setPendingChallenge(Map.of(
                "method", "sms",
                "challengeId", smsResult.enrollmentId(),
                "tan", smsResult.tan()
        ));
        return new NextStep.SmsTanInputNextStep(smsResult.enrollmentId(), smsResult.tan());
    }

    public NextStep verify(BindingSession session, SmsVerifyCommand request) {
        Long accountId = requireAccountId(session);
        Long enrollmentId = request == null ? null : request.enrollmentId();
        String tan = request == null ? null : request.tan();

        authSmsService.validateTan(enrollmentId, tan);

        boolean wasSetup = !accountService.hasActiveAuthenticationMethod(accountId);
        if (wasSetup) {
            accountService.addAuthenticationMethod(
                    accountId,
                    "sms",
                    true,
                    Map.of("enrollmentId", enrollmentId, "enrollmentRef", enrollmentId)
            );
        }
        session.clearPendingChallenge();
        session.getData().remove("selectedAuthenticationMethod");
        session.setPhase("authenticated");
        Long personId = accountService.findAccountProfile(accountId)
                .map(profile -> profile.personId())
                .orElse(null);
        return new NextStep.AuthenticationCompletedNextStep(accountId, personId);
    }

    private Long requireAccountId(BindingSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            throw new FlowSessionException("No account linked to this session");
        }
        return accountId;
    }

}
