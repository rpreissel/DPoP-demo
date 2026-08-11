package com.example.dpop.orchestrator.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegistrationSessionRepository extends JpaRepository<RegistrationSession, UUID> {

    Optional<RegistrationSession> findByJwkThumbprint(String jwkThumbprint);
}
