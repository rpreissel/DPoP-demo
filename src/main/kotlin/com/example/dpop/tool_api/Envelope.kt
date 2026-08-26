package com.example.dpop.tool_api

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(
    description = "One active authentication method instance. `id` addresses it for DELETE .../methods/{id} - " +
        "method name alone isn't unique when a method allows multiple instances (docs/03-tool-architektur.md, " +
        "e.g. several active `device` entries, one per physical device). `label` is a user-chosen display name, " +
        "set only for multi-instance methods - null for singleton ones (email/sms/password), which the client " +
        "labels from `method` itself."
)
data class ActiveMethodView(
    val id: String,
    val method: String,
    val label: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelBlock(
    val channelSessionId: UUID,
    val state: String,
    val currentAcr: String? = null,
    val currentAmr: List<String>? = null,
    @field:Schema(
        description = "All active authentication methods on the account, regardless of whether this session's " +
            "currentAmr proved them. Distinct from currentAmr on purpose: currentAmr is session evidence (what THIS " +
            "channel actually proved), activeMethods is the account's full standing method list (docs/10-frontend.md). " +
            "Deliberately unfiltered by device - a lost/stolen device's credential must be removable from ANY " +
            "authenticated session, not only from that device itself."
    )
    val activeMethods: List<ActiveMethodView>? = null
)

/**
 * The one response envelope for every endpoint of every layer (docs/05-api.md #2) - channel-
 * level and tool-level alike, so the client needs exactly one apply-function and never a follow-
 * up `GET /channels` after a tool completes. `channel` carries what used to be flat top-level
 * fields; `next.toolSessionId` is now the only carrier of a tool's session id (no more separate
 * top-level `toolSessionId`).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChannelResponse(
    val channel: ChannelBlock,
    val next: Next? = null,
    val stepData: Map<String, Any?>? = null,
    @field:Schema(description = "Demo-only correlation IDs, never part of the production contract (docs/05-api.md #2).")
    val demo: DemoInfo? = null
)

/**
 * [values] is whatever a tool handler attached via `demoData(...)` (tool_spi.DEMO_DATA_KEY) -
 * e.g. `tan`, `password` - flattened directly into the JSON object alongside accountId/personId
 * so the frontend contract stays `demo.tan`/`demo.password` regardless of which tool produced it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DemoInfo(
    val accountId: Long? = null,
    val personId: Long? = null,
    @get:JsonAnyGetter val values: Map<String, Any?> = emptyMap()
)
