package com.example.dpop.orchestrator.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BindingSessionRepository extends JpaRepository<BindingSession, String> {

    Optional<BindingSession> findByBindingKeyRef(String bindingKeyRef);
}
