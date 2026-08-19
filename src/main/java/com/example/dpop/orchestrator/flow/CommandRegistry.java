package com.example.dpop.orchestrator.flow;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandRegistry {

    private final Map<CommandKey, CommandRegistration<?>> registrations = new HashMap<>();

    public <T> void register(CommandKey key, CommandRegistration<T> registration) {
        registrations.put(key, registration);
    }

    public CommandRegistration<?> require(CommandKey key) {
        CommandRegistration<?> registration = registrations.get(key);
        if (registration == null) {
            throw new IllegalArgumentException("Unsupported command: " + key.method() + ":" + key.action());
        }
        return registration;
    }

    public Map<CommandKey, CommandRegistration<?>> registrations() {
        return Collections.unmodifiableMap(registrations);
    }
}
