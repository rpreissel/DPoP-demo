package com.example.dpop.orchestrator.api.v1.identification.fsc;

import java.util.List;

public sealed interface FscStep {
    record NeedInput(List<String> missingFields) implements FscStep {}
    record Verify(String kvnr, String fsc)       implements FscStep {}
}
