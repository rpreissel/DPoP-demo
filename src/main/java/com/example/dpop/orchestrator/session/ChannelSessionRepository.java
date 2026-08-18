package com.example.dpop.orchestrator.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelSessionRepository extends JpaRepository<ChannelSession, UUID> {
    Optional<ChannelSession> findByBindingKeyRef(String bindingKeyRef);
}
