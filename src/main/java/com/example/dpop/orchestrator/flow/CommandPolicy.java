package com.example.dpop.orchestrator.flow;

import java.util.Set;

public record CommandPolicy(Set<String> allowedPhases,
                            Set<String> requiredFlags,
                            Set<String> forbiddenFlags,
                            String nextPhase) {
}
