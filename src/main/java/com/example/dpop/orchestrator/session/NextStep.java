package com.example.dpop.orchestrator.session;

import java.util.List;

public record NextStep(
        String context,
        String step,
        List<String> identificationMethods
) {
}
