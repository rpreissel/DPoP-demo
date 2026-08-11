package com.example.dpop.orchestrator.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "client_session")
public class ClientSession {

    @Id
    @Column(name = "jwk_thumbprint", nullable = false, length = 64)
    private String jwkThumbprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private SessionType type;

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Column(name = "last_accessed", nullable = false)
    private Instant lastAccessed;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10)
    private SessionFormat format;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "JSON")
    private Map<String, Object> data;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ClientSession() {
    }

    public ClientSession(String jwkThumbprint, SessionType type, Instant expireAt, SessionFormat format, Map<String, Object> data) {
        this.jwkThumbprint = jwkThumbprint;
        this.type = type;
        this.expireAt = expireAt;
        this.lastAccessed = Instant.now();
        this.format = format;
        this.data = data;
    }

    public static ClientSession createRegistration(String jwkThumbprint, Instant expireAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", UUID.randomUUID().toString());
        return new ClientSession(jwkThumbprint, SessionType.REG, expireAt, SessionFormat.V1, data);
    }

    public static ClientSession createAuthorisation(String jwkThumbprint, Instant expireAt) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", UUID.randomUUID().toString());
        return new ClientSession(jwkThumbprint, SessionType.AUTH, expireAt, SessionFormat.V1, data);
    }

    public String getJwkThumbprint() {
        return jwkThumbprint;
    }

    public SessionType getType() {
        return type;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public Instant getLastAccessed() {
        return lastAccessed;
    }

    public SessionFormat getFormat() {
        return format;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public Long getVersion() {
        return version;
    }

    public UUID getSessionId() {
        Object id = data.get("id");
        return id == null ? null : UUID.fromString(id.toString());
    }

    public Long getPersonId() {
        Object personId = data.get("personId");
        return personId == null ? null : Long.valueOf(personId.toString());
    }

    public void setPersonId(Long personId) {
        data.put("personId", personId);
    }

    public void touch() {
        this.lastAccessed = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expireAt);
    }
}
