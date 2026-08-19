package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsChallengeResult;
import com.example.dpop.auth_sms.AuthSmsEnrollResult;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.orchestrator.flow.AuthenticationMethodHandler;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.session.BindingSession;
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

    public NextStep start(BindingSession session, Map<String, Object> request) {
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
            String phoneNumber = getString(request, "phoneNumber");
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

    public NextStep verify(BindingSession session, Map<String, Object> request) {
        Long accountId = requireAccountId(session);
        Long enrollmentId = getLong(request, "enrollmentId");
        String tan = getString(request, "tan");

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
