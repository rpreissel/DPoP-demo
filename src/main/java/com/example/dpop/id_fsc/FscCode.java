package com.example.dpop.id_fsc;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "fsc_code")
public class FscCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long personId;

    private String code;

    private Instant expiresAt;

    protected FscCode() {
    }

    public FscCode(Long personId, String code, Instant expiresAt) {
        this.personId = personId;
        this.code = code;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public String getCode() {
        return code;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isValid() {
        return expiresAt != null && Instant.now().isBefore(expiresAt);
    }
}
