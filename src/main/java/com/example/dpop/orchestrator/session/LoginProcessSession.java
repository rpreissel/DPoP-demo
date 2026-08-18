package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
@DiscriminatorValue("LOGIN")
public class LoginProcessSession extends ProcessSession {

    @Column(name = "selected_authentication_method", length = 100)
    private String selectedAuthenticationMethod;

    protected LoginProcessSession() {
    }

    public LoginProcessSession(UUID channelSessionId, Instant expiresAt) {
        super(channelSessionId, ProcessPurpose.LOGIN, expiresAt);
    }

    public String getSelectedAuthenticationMethod() {
        return selectedAuthenticationMethod;
    }

    public void setSelectedAuthenticationMethod(String method) {
        this.selectedAuthenticationMethod = method;
    }
}
