package com.example.dpop.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import java.time.Instant
import java.util.UUID

@Entity
@DiscriminatorValue("STEP_UP")
class StepUpProcessSession : ProcessSession {

    @Column(name = "required_acr", length = 100)
    var requiredAcr: String? = null

    @Column(name = "starting_acr", length = 100)
    var startingAcr: String? = null

    @Column(name = "achieved_acr", length = 100)
    var achievedAcr: String? = null

    protected constructor() : super()

    constructor(channelSessionId: UUID?, requiredAcr: String?, expiresAt: Instant?) : super(
        channelSessionId, ProcessPurpose.STEP_UP, expiresAt
    ) {
        this.requiredAcr = requiredAcr
    }
}
