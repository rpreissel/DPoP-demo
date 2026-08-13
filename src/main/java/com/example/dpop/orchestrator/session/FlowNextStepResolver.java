package com.example.dpop.orchestrator.session;

import com.example.dpop.account.Account;
import com.example.dpop.account.AccountService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FlowNextStepResolver {

    private final AccountService accountService;
    private final IdentificationMethodProvider identificationMethodProvider;
    private final AuthenticationMethodProvider authenticationMethodProvider;

    public FlowNextStepResolver(AccountService accountService,
                                IdentificationMethodProvider identificationMethodProvider,
                                AuthenticationMethodProvider authenticationMethodProvider) {
        this.accountService = accountService;
        this.identificationMethodProvider = identificationMethodProvider;
        this.authenticationMethodProvider = authenticationMethodProvider;
    }

    public NextStep resolve(ClientSession session) {
        if ("authenticated".equals(session.getPhase())) {
            return new NextStep.AuthenticationCompletedNextStep();
        }

        Map<String, Object> pendingChallenge = session.getPendingChallenge();
        if (pendingChallenge != null) {
            String method = String.valueOf(pendingChallenge.get("method"));
            Long challengeId = ((Number) pendingChallenge.get("challengeId")).longValue();
            String tan = String.valueOf(pendingChallenge.get("tan"));
            return new NextStep.SmsTanInputNextStep(challengeId, tan);
        }

        Long accountId = session.getAccountId();
        if (accountId != null) {
            List<String> activeMethods = accountService.findById(accountId)
                    .map(authenticationMethodProvider::activeMethods)
                    .orElse(List.of());

            String selectedAuthMethod = session.getSelectedAuthenticationMethod();
            if (!activeMethods.isEmpty()) {
                if (selectedAuthMethod != null && activeMethods.contains(selectedAuthMethod)) {
                    return createChallengeNextStep(session, accountId, selectedAuthMethod);
                }
                return new NextStep.AuthenticationMethodSelectionNextStep(activeMethods);
            }
            return new NextStep.AuthenticationSetupNextStep(authenticationMethodProvider.availableMethods());
        }

        Long personId = session.getPersonId();
        if (personId != null) {
            return new NextStep.FscInputNextStep();
        }

        return new NextStep.UseIdentificationMethodNextStep(identificationMethodProvider.availableMethods());
    }

    private NextStep createChallengeNextStep(ClientSession session, Long accountId, String method) {
        if ("sms".equals(method)) {
            return new NextStep.SmsTanInputNextStep(null, null);
        }
        throw new IllegalStateException("Unsupported authentication method: " + method);
    }
}
