package com.example.dpop.orchestrator.flow;

public record CommandRegistration<T>(Class<T> requestType,
                                     CommandPolicy policy,
                                     CommandExecutor<T> executor) {
}
