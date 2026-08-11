package com.example.dpop.orchestrator.session;

import java.util.List;

public record NextStep(
        String type,
        List<String> identificationMethods
) {
}
