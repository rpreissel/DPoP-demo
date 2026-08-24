package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.orchestrator.orchestration.Next
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(
    description = "requiredAcr is a lower bound only. Always creates a brand-new ChannelSession for this device " +
        "(docs/02-domaenenmodell.md #3) - DPoP proves the device, never a lookup key for resuming a session. To end " +
        "a previous session first (logout), call DELETE .../channels/{channelSessionId} before this."
)
data class ChannelCreateRequest(
    val requiredAcr: String? = null,
    @field:Schema(
        description = "auto (default): DeviceAccountLink found -> LOGIN, else REGISTRATION. " +
            "login: always offers lookup-based login (email + credential), even on a linked device. " +
            "register: always starts fresh REGISTRATION, even on a linked device (second account).",
        example = "auto",
        allowableValues = ["auto", "login", "register"]
    )
    val intent: String? = null
)

@Schema(description = "Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md #9).")
data class ChannelPatchRequest(
    @field:Schema(example = "loa3")
    val requiredAcr: String
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelResponse(
    val channelSessionId: UUID,
    val state: String,
    val currentAcr: String? = null,
    val currentAmr: List<String>? = null,
    @field:Schema(
        description = "All active authentication methods on the account, regardless of whether this session's " +
            "currentAmr proved them. Distinct from currentAmr on purpose: currentAmr is session evidence (what THIS " +
            "channel actually proved), activeMethods is the account's full standing method list (docs/10-frontend.md)."
    )
    val activeMethods: List<String>? = null,
    val stepData: Map<String, Any?>? = null,
    val next: Next? = null
)
