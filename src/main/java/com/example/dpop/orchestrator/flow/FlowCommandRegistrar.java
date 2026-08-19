package com.example.dpop.orchestrator.flow;

import com.example.dpop.orchestrator.flow.handler.FscIdentificationHandler;
import com.example.dpop.orchestrator.flow.handler.PasswordAuthenticationHandler;
import com.example.dpop.orchestrator.flow.handler.SmsAuthenticationHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class FlowCommandRegistrar {

    public FlowCommandRegistrar(CommandRegistry commandRegistry,
                                FscIdentificationHandler fscIdentificationHandler,
                                SmsAuthenticationHandler smsAuthenticationHandler,
                                PasswordAuthenticationHandler passwordAuthenticationHandler) {
        commandRegistry.register(
                new CommandKey("fsc", "start"),
                new CommandRegistration(
                        Map.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        fscIdentificationHandler::start
                )
        );
        commandRegistry.register(
                new CommandKey("fsc", "submit"),
                new CommandRegistration(
                        Map.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        fscIdentificationHandler::submit
                )
        );
        commandRegistry.register(
                new CommandKey("sms", "start"),
                new CommandRegistration(
                        Map.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), null),
                        smsAuthenticationHandler::start
                )
        );
        commandRegistry.register(
                new CommandKey("sms", "verify"),
                new CommandRegistration(
                        Map.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), "authenticated"),
                        smsAuthenticationHandler::verify
                )
        );
        commandRegistry.register(
                new CommandKey("password", "verify"),
                new CommandRegistration(
                        Map.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), "authenticated"),
                        passwordAuthenticationHandler::verify
                )
        );
    }
}
