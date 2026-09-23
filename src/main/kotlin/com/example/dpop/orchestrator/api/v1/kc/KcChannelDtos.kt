package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.channel.AmrEntry
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Upsert body for the kc-facade's one facade-specific endpoint (docs/05-api.md " +
        "Abschnitt 3). All fields are optional. accountId is the account " +
        "Keycloak already knows (sub vorhanden) - the channel is bound to it immediately, once, " +
        "never overwritten by a later call. targetAcr is Keycloak's requested LoA level, already " +
        "translated into an orchestrator ACR string, and only raises the channel's floor, never " +
        "lowers it. amr lists which native Keycloak authenticators (never orchestrator tools) " +
        "just proved something THIS flow run, one entry per proof - method/loa/factorTypes are " +
        "resolved server-side from a NativeAuthenticatorDescriptor (see AmrEntry), the kc-facade's " +
        "own mirror of a ToolDescriptor, not resolved from the orchestrator's own catalog (which " +
        "stays entirely ignorant of native authenticators). Merged into the channel's evidence " +
        "and re-checked against the current floor exactly like any other proof; no separate " +
        "'combined native acr' field exists, since the orchestrator derives that itself."
)
data class KcChannelUpsertRequest(
    @field:Schema(example = "42")
    val accountId: Long? = null,
    @field:Schema(example = "loa2")
    val targetAcr: String? = null,
    val amr: List<AmrEntry>? = null,
    @field:Schema(
        description = "A signed RestoreData token this same UserSession's channel returned " +
            "earlier via GET .../restore-data, resubmitted verbatim (docs/ideen/web-keycloak-" +
            "kanal.md #6) - the bulk, one-shot way to seed a brand-new channel with what a PRIOR, " +
            "unrelated flow run already established, as opposed to accountId/amr above which " +
            "report what THIS flow run just proved. Both are merged into the channel the same " +
            "way; only restoreData may already be meaningfully old by the time it arrives here. " +
            "Opaque to every caller but the orchestrator itself - see RestoreDataCodec."
    )
    val restoreData: String? = null,
    @field:Schema(
        description = "Required whenever restoreData is present, ignored otherwise. Keycloak's " +
            "own, durable UserSessionModel id - deliberately NOT read off the peer-auth assertion " +
            "(the assertion's kc-anchor is always THIS flow run's own channelSessionId, " +
            "docs/02-domaenenmodell.md Abschnitt 1, so it can't verify a token minted for a DIFFERENT, " +
            "earlier flow run's channel). Must match what GET .../restore-data was called with to " +
            "produce this exact restoreData token."
    )
    val kcSessionId: String? = null,
    @field:Schema(
        description = "The Web channel's own declaration of which toolIds its Keycloak theme can " +
            "render (one com.example.dpop.kcext.webtool.WebToolRenderer factory " +
            "per toolId, registered via META-INF/services) - the kc-facade's counterpart to the App " +
            "channel's own availableTools (POST /channels). Only read on this channel's first call " +
            "(a later upsert resumes the already-persisted set); a channel-anonymous caller that " +
            "omits this gets none of the orchestrator's tools, never all of them."
    )
    val availableTools: List<String>? = null,
    @field:Schema(
        description = "Only read on this channel's first call, same restriction as availableTools " +
            "- the kc facade's own, deliberately narrow counterpart to the App facade's `intent` " +
            "request parameter (docs/05-api.md #\"POST /app/channels: intent-Parameter\"). Omitted " +
            "(or null) means kc_select_method, the existing login/step-up behaviour. Only " +
            "kc_select_method and register are accepted here - unlike the App facade, not every " +
            "AuthIntent.isEntryIntent value: fast_access/lookup_login assume an APP-shaped channel " +
            "this facade never has.",
        example = "register"
    )
    val intent: String? = null
)

/** Wire wrapper for `GET .../restore-data` (docs/05-api.md Abschnitt 3) - a plain string response body would be an unusual shape next to the rest of this JSON API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The channel's current RestoreData, signed - null if there is nothing worth restoring yet.")
data class RestoreDataResponse(val restoreData: String? = null)
