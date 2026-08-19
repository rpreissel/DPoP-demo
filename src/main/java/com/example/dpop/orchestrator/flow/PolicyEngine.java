package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.session.BindingSession;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PolicyEngine {

    public void validate(CommandPolicy policy, BindingSession session, Map<String, Object> request) {
        if (policy == null) {
            return;
        }
        if (!policy.allowedPhases().isEmpty() && !policy.allowedPhases().contains(session.getPhase())) {
            throw new FlowSessionException("Command not allowed in phase " + session.getPhase());
        }
        for (String requiredFlag : policy.requiredFlags()) {
            if (!hasFlag(session, request, requiredFlag)) {
                throw new FlowSessionException("Missing required flag: " + requiredFlag);
            }
        }
        for (String forbiddenFlag : policy.forbiddenFlags()) {
            if (hasFlag(session, request, forbiddenFlag)) {
                throw new FlowSessionException("Forbidden flag present: " + forbiddenFlag);
            }
        }
    }

    private boolean hasFlag(BindingSession session, Map<String, Object> request, String flag) {
        if (flag.equals("account.linked")) {
            return session.getAccountId() != null;
        }
        if (flag.equals("sms.active")) {
            return Boolean.TRUE.equals(session.getData().get("sms.active"));
        }
        if (flag.equals("password.active")) {
            return Boolean.TRUE.equals(session.getData().get("password.active"));
        }
        if (flag.startsWith("pending.")) {
            String key = flag.substring("pending.".length());
            return session.getPendingChallenge() != null && session.getPendingChallenge().containsKey(key);
        }
        return request.containsKey(flag) || Boolean.TRUE.equals(session.getData().get(flag));
    }
}
