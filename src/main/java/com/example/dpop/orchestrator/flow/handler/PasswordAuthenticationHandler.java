package com.example.dpop.orchestrator.flow.handler;

import com.example.dpop.orchestrator.flow.CommandKey;
import com.example.dpop.orchestrator.flow.CommandPolicy;
import com.example.dpop.orchestrator.flow.CommandRegistration;
import com.example.dpop.orchestrator.flow.CommandRegistry;
import com.example.dpop.orchestrator.flow.FlowSessionException;
import com.example.dpop.orchestrator.flow.command.PasswordVerifyCommand;
import com.example.dpop.orchestrator.session.BindingSession;
import com.example.dpop.orchestrator.session.NextStep;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class PasswordAuthenticationHandler {

    private final CommandRegistry commandRegistry;

    public PasswordAuthenticationHandler(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    public String method() {
        return "password";
    }

    @PostConstruct
    void registerCommands() {
        commandRegistry.register(
                new CommandKey("password", "verify"),
                new CommandRegistration<>(
                        PasswordVerifyCommand.class,
                        new CommandPolicy(Set.of(), Set.of(), Set.of(), "authenticated"),
                        this::verify
                )
        );
    }

    public NextStep start(BindingSession session, Map<String, Object> request) {
        throw new FlowSessionException("Password authentication is not wired to a backend yet");
    }

    public NextStep verify(BindingSession session, PasswordVerifyCommand request) {
        throw new FlowSessionException("Password authentication is not wired to a backend yet");
    }
}
