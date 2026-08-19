package com.example.dpop.orchestrator.flow;

public record CommandRegistration(Class<?> requestType,
                                  CommandPolicy policy,
                                  CommandExecutor executor) {
}
