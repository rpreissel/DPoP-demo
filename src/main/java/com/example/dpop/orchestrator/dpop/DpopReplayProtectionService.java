package com.example.dpop.orchestrator.dpop;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DpopReplayProtectionService {

    private final Map<String, Instant> seenProofs = new ConcurrentHashMap<>();

    public void validateAndStore(String thumbprint, String jti, Instant expiresAt) {
        cleanupExpiredEntries();

        String key = thumbprint + ":" + jti;
        Instant existing = seenProofs.putIfAbsent(key, expiresAt);
        if (existing != null && existing.isAfter(Instant.now())) {
            throw new DpopValidationException("DPoP proof replay detected");
        }

        seenProofs.put(key, expiresAt);
    }

    private void cleanupExpiredEntries() {
        Instant now = Instant.now();
        seenProofs.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
