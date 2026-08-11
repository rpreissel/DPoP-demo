package com.example.dpop.orchestrator.authorisation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthorisationSessionRepository extends JpaRepository<AuthorisationSession, UUID> {

    Optional<AuthorisationSession> findByJwkThumbprint(String jwkThumbprint);
}
