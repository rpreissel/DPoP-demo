package com.example.dpop.tool_api

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(
    description = "One active authentication method instance. `id` addresses it for " +
        "DELETE .../methods/{id} - method name alone isn't unique when a method allows multiple " +
        "instances (e.g. several active `device` entries, one per physical device). `label` is a " +
        "user-chosen display name, set only for multi-instance methods; `null` for singleton ones " +
        "(email/sms/password), which the client labels from `method` itself."
)
data class ActiveMethodView(
    @field:Schema(example = "7f3e2b1a-0c9d-4e8f-8a1b-2c3d4e5f6a7b")
    val id: String,
    @field:Schema(example = "sms")
    val method: String,
    @field:Schema(example = "Laptop")
    val label: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelBlock(
    val channelSessionId: UUID,
    @field:Schema(example = "AUTHENTICATED")
    val state: String,
    @field:Schema(example = "loa2")
    val currentAcr: String? = null,
    @field:Schema(example = "[\"sms\", \"password\"]")
    val currentAmr: List<String>? = null,
    @field:Schema(
        description = "All active authentication methods on the account, regardless of whether " +
            "this session's currentAmr proved them. currentAmr is session evidence (what THIS " +
            "channel actually proved); activeMethods is the account's full standing method list, " +
            "unfiltered by device - a lost/stolen device's credential must be removable from any " +
            "authenticated session, not only from that device itself."
    )
    val activeMethods: List<ActiveMethodView>? = null
)

/**
 * The response envelope for every channel- and tool-level endpoint. `channel` carries the
 * current channel state; `next` addresses the client's next step; a tool completing never
 * requires a follow-up `GET /channels` - `channel` already reflects the post-outcome state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelResponse(
    val channel: ChannelBlock,
    val next: Next? = null,
    @field:Schema(
        description = "Whatever the current step needs to render: missing fields, selection options, or a retry reason.",
        example = "{\"options\": [\"ident-fsc\", \"ident-eid\"]}"
    )
    val stepData: Map<String, Any?>? = null,
    @field:Schema(description = "Demo-only correlation IDs, never part of the production contract.")
    val demo: DemoInfo? = null
)

/**
 * Demo-only values a tool handler attached (e.g. a plaintext `tan`), flattened into the JSON
 * object alongside [accountId]/[personId] so the client can read `demo.tan` regardless of which
 * tool produced it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DemoInfo(
    @field:Schema(example = "42")
    val accountId: Long? = null,
    @field:Schema(example = "7")
    val personId: Long? = null,
    @field:Schema(
        description = "Flattened onto the parent object (e.g. demo.tan, demo.email) - whichever " +
            "demo-only values the tool that just ran attached, e.g. a plaintext tan/code/password.",
        example = "{\"tan\": \"123456\", \"email\": \"max.mustermann@example.com\"}"
    )
    @get:JsonAnyGetter val values: Map<String, Any?> = emptyMap()
)
