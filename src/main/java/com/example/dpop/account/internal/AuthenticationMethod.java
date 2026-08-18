package com.example.dpop.account.internal;

import java.time.Instant;
import java.util.Map;

public class AuthenticationMethod {

    private String method;
    private boolean active;
    private Instant createdAt;
    private Map<String, Object> details;

    protected AuthenticationMethod() {
    }

    public AuthenticationMethod(String method,
                                boolean active,
                                Instant createdAt,
                                Map<String, Object> details) {
        this.method = method;
        this.active = active;
        this.createdAt = createdAt;
        this.details = details;
    }

    public String getMethod() {
        return method;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
