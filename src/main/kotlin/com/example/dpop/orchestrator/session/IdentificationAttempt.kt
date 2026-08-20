package com.example.dpop.orchestrator.session

import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import java.time.Instant
import java.util.UUID

@Entity
@DiscriminatorValue("identification")
class IdentificationAttempt : OrchestratorAttempt {

    protected constructor() : super()

    constructor(
        processSessionId: UUID?,
        nextContext: String?,
        nextStep: String?,
        expiresAt: Instant?
    ) : super(processSessionId, nextContext, nextStep, expiresAt)
}
