package com.example.dpop.orchestrator.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrchestratorAttemptRepository extends JpaRepository<OrchestratorAttempt, UUID> {
    @Query("SELECT a FROM OrchestratorAttempt a WHERE a.processSessionId = :processSessionId ORDER BY a.createdAt DESC LIMIT 1")
    Optional<OrchestratorAttempt> findLatestByProcessSessionId(UUID processSessionId);

    List<OrchestratorAttempt> findByProcessSessionIdOrderByCreatedAtDesc(UUID processSessionId);
}
