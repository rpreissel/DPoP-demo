package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
@DiscriminatorValue("REGISTRATION")
public class RegistrationProcessSession extends ProcessSession {

    @Column(name = "person_id")
    private Long personId;

    @Column(name = "selected_identification_method", length = 100)
    private String selectedIdentificationMethod;

    @Column(name = "selected_authentication_method", length = 100)
    private String selectedAuthenticationMethod;

    protected RegistrationProcessSession() {
    }

    public RegistrationProcessSession(UUID channelSessionId, Instant expiresAt) {
        super(channelSessionId, ProcessPurpose.REGISTRATION, expiresAt);
    }

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public String getSelectedIdentificationMethod() {
        return selectedIdentificationMethod;
    }

    public void setSelectedIdentificationMethod(String method) {
        this.selectedIdentificationMethod = method;
    }

    public String getSelectedAuthenticationMethod() {
        return selectedAuthenticationMethod;
    }

    public void setSelectedAuthenticationMethod(String method) {
        this.selectedAuthenticationMethod = method;
    }
}
