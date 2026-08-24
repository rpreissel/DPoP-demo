package com.example.dpop.orchestrator.session

import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import java.time.Instant
import java.util.UUID

/**
 * Voluntary enrollment on an already-AUTHENTICATED channel (docs/04-orchestrierung.md): unlike
 * REGISTRATION/STEP_UP, finishing does not depend on AuthPolicy.canAccountReach/isSatisfied - one
 * successful Enrolled outcome ends the process and returns to AUTHENTICATED. Empty marker
 * subclass like LoginProcessSession: no purpose-specific fields needed beyond the base accountId.
 */
@Entity
@DiscriminatorValue("MANAGE_METHODS")
class ManageMethodsProcessSession : ProcessSession {

    protected constructor() : super()

    constructor(channelSessionId: UUID?, expiresAt: Instant?) : super(channelSessionId, ProcessPurpose.MANAGE_METHODS, expiresAt)
}
