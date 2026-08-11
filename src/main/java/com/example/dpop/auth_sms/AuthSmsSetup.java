package com.example.dpop.auth_sms;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "auth_sms")
public class AuthSmsSetup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phoneNumber;

    private String tan;

    private boolean validated;

    private Instant createdAt;

    private Instant updatedAt;

    protected AuthSmsSetup() {
    }

    public AuthSmsSetup(String phoneNumber,
                        String tan,
                        boolean validated,
                        Instant createdAt,
                        Instant updatedAt) {
        this.phoneNumber = phoneNumber;
        this.tan = tan;
        this.validated = validated;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getTan() {
        return tan;
    }

    public boolean isValidated() {
        return validated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setValidated(boolean validated) {
        this.validated = validated;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
