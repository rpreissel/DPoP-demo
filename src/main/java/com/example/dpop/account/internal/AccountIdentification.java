package com.example.dpop.account.internal;

import java.time.Instant;
import java.util.Map;

public class AccountIdentification {

    private String identificationMethod;
    private String identificationQuality;
    private Instant identifiedAt;
    private String registrationSessionId;
    private Map<String, Object> details;

    protected AccountIdentification() {
    }

    public AccountIdentification(String identificationMethod,
                                 String identificationQuality,
                                 Instant identifiedAt,
                                 String registrationSessionId,
                                 Map<String, Object> details) {
        this.identificationMethod = identificationMethod;
        this.identificationQuality = identificationQuality;
        this.identifiedAt = identifiedAt;
        this.registrationSessionId = registrationSessionId;
        this.details = details;
    }

    public String getIdentificationMethod() {
        return identificationMethod;
    }

    public String getIdentificationQuality() {
        return identificationQuality;
    }

    public Instant getIdentifiedAt() {
        return identifiedAt;
    }

    public String getRegistrationSessionId() {
        return registrationSessionId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
