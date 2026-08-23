package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import java.time.Instant
import java.util.UUID

@Entity
@DiscriminatorValue("REGISTRATION")
class RegistrationProcessSession : ProcessSession {

    /** Set once ident-* resolves the identity (docs/04-orchestrierung.md, Completed.Identified). */
    @Column(name = "person_id")
    var personId: Long? = null

    protected constructor() : super()

    constructor(channelSessionId: UUID?, expiresAt: Instant?) : super(channelSessionId, ProcessPurpose.REGISTRATION, expiresAt)
}
