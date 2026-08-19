package com.example.dpop.orchestrator.api.v1.identification.fsc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record FscPending(String kvnr, String fsc) {

    public static FscPending empty() {
        return new FscPending(null, null);
    }

    public FscPending merge(Map<String, Object> patch) {
        if (patch == null) return this;
        String newKvnr = patch.containsKey("kvnr") ? (String) patch.get("kvnr") : kvnr;
        String newFsc = patch.containsKey("fsc") ? (String) patch.get("fsc") : fsc;
        return new FscPending(newKvnr, newFsc);
    }

    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (kvnr == null || kvnr.isBlank()) missing.add("kvnr");
        if (fsc == null || fsc.isBlank()) missing.add("fsc");
        return missing;
    }

    public boolean isComplete() { return missingFields().isEmpty(); }
}
