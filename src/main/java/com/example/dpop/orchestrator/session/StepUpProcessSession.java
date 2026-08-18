package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.Instant;
import java.util.UUID;

@Entity
@DiscriminatorValue("STEP_UP")
public class StepUpProcessSession extends ProcessSession {

    @Column(name = "required_acr", length = 100)
    private String requiredAcr;

    @Column(name = "starting_acr", length = 100)
    private String startingAcr;

    @Column(name = "achieved_acr", length = 100)
    private String achievedAcr;

    @Column(name = "selected_authentication_method", length = 100)
    private String selectedAuthenticationMethod;

    protected StepUpProcessSession() {
    }

    public StepUpProcessSession(UUID channelSessionId, String requiredAcr, Instant expiresAt) {
        super(channelSessionId, ProcessPurpose.STEP_UP, expiresAt);
        this.requiredAcr = requiredAcr;
    }

    public String getRequiredAcr() {
        return requiredAcr;
    }

    public String getStartingAcr() {
        return startingAcr;
    }

    public void setStartingAcr(String acr) {
        this.startingAcr = acr;
    }

    public String getAchievedAcr() {
        return achievedAcr;
    }

    public void setAchievedAcr(String acr) {
        this.achievedAcr = acr;
    }

    public String getSelectedAuthenticationMethod() {
        return selectedAuthenticationMethod;
    }

    public void setSelectedAuthenticationMethod(String method) {
        this.selectedAuthenticationMethod = method;
    }
}
