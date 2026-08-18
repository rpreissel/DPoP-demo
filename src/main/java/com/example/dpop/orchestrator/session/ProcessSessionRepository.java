package com.example.dpop.orchestrator.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessSessionRepository extends JpaRepository<ProcessSession, UUID> {
    Optional<ProcessSession> findByChannelSessionIdAndPurpose(UUID channelSessionId, ProcessPurpose purpose);

    List<ProcessSession> findByChannelSessionIdOrderByCreatedAtDesc(UUID channelSessionId);
}
