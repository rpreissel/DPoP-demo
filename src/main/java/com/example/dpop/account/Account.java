package com.example.dpop.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long personId;

    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<AccountIdentification> identifications = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private List<AuthenticationMethod> authenticationMethods = new ArrayList<>();

    protected Account() {
    }

    public Account(Long personId, Instant createdAt) {
        this.personId = personId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<AccountIdentification> getIdentifications() {
        return identifications;
    }

    public List<AuthenticationMethod> getAuthenticationMethods() {
        return authenticationMethods;
    }

    public void addIdentification(AccountIdentification identification) {
        identifications.add(identification);
    }

    public void addAuthenticationMethod(AuthenticationMethod authenticationMethod) {
        authenticationMethods.add(authenticationMethod);
    }
}
