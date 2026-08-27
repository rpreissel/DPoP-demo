package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.tool_api.ActiveMethodView
import io.swagger.v3.oas.annotations.media.Schema

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

@Schema(
    description = "An answer to whatever the current step is waiting on instead of a tool run " +
        "(docs/04-orchestrierung.md #3) - e.g. \"accept\"/\"decline\" for the optional device-binding offer of a " +
        "lookup login. Which values are valid depends on what next.step is currently offering."
)
data class AnswerRequest(
    @field:Schema(example = "accept")
    val answer: String
)

@Schema(description = "Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md #9).")
data class ChannelPatchRequest(
    @field:Schema(example = "loa3")
    val requiredAcr: String
)

@Schema(description = "The account's active authentication methods (docs/05-api.md #2). Never contains fsc.")
data class MethodsResponse(
    val methods: List<ActiveMethodView>
)
