package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.auth_sms.AuthSmsSetup;
import com.example.dpop.auth_sms.AuthSmsSetupResult;
import com.example.dpop.orchestrator.flow.AuthenticationMethodHandler;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.NextStep;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SmsAuthenticationHandler implements AuthenticationMethodHandler {

    private final AuthSmsService authSmsService;
    private final AccountService accountService;

    public SmsAuthenticationHandler(AuthSmsService authSmsService,
                                    AccountService accountService) {
        this.authSmsService = authSmsService;
        this.accountService = accountService;
    }

    @Override
    public String method() {
        return "sms";
    }

    @Override
    public NextStep start(ClientSession session, Map<String, Object> request) {
        Long accountId = requireAccountId(session);
        boolean isChallenge = accountService.hasActiveAuthenticationMethod(accountId);

        String phoneNumber;
        if (isChallenge) {
            phoneNumber = accountService.findActiveSmsPhoneNumber(accountId)
                    .orElseThrow(() -> new FlowSessionException("No active sms authentication method configured"));
        } else {
            phoneNumber = getString(request, "phoneNumber");
            if (phoneNumber == null || phoneNumber.isBlank()) {
                throw new FlowSessionException("phoneNumber is required for sms setup");
            }
        }

        AuthSmsSetupResult smsResult = authSmsService.setupSms(phoneNumber);

        session.setSelectedAuthenticationMethod("sms");
        session.setPendingChallenge(Map.of(
                "method", "sms",
                "challengeId", smsResult.smsSetupId(),
                "tan", smsResult.tan()
        ));
        return new NextStep.SmsTanInputNextStep(smsResult.smsSetupId(), smsResult.tan());
    }

    @Override
    public NextStep verify(ClientSession session, Map<String, Object> request) {
        Long accountId = requireAccountId(session);
        Long smsSetupId = getLong(request, "smsSetupId");
        String tan = getString(request, "tan");

        AuthSmsSetup validatedSetup = authSmsService.validateTan(smsSetupId, tan);

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
        Long personId = accountService.findById(accountId)
                .map(account -> account.getPersonId())
                .orElse(null);
        return new NextStep.AuthenticationCompletedNextStep(accountId, personId);
    }

    private Long requireAccountId(ClientSession session) {
        Long accountId = session.getAccountId();
        if (accountId == null) {
            throw new FlowSessionException("No account linked to this session");
        }
        return accountId;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }
}
