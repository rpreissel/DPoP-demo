package com.example.dpop.orchestrator.session

import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import java.time.Instant
import java.util.UUID

@Entity
@DiscriminatorValue("LOGIN")
class LoginProcessSession : ProcessSession {

    protected constructor() : super()

    constructor(channelSessionId: UUID?, expiresAt: Instant?) : super(channelSessionId, ProcessPurpose.LOGIN, expiresAt)
}
