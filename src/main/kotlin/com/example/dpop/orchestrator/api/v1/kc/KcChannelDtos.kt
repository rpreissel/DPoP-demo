package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.policy.AuthEvidence
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Upsert body for the kc-facade's one facade-specific endpoint (docs/ideen/" +
        "web-keycloak-kanal.md #6/#8/#9). All fields are optional. accountId is the account " +
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
            "(the assertion's kc-anchor is always THIS flow run's own channelSessionId, docs/" +
            "ideen/web-keycloak-kanal.md #4, so it can't verify a token minted for a DIFFERENT, " +
            "earlier flow run's channel). Must match what GET .../restore-data was called with to " +
            "produce this exact restoreData token."
    )
    val kcSessionId: String? = null
)

/**
 * One method a native Keycloak authenticator just proved THIS flow run (docs/ideen/web-keycloak-
 * kanal.md #6/#8/#9). Deliberately just two ids, not method/loa/factorTypes too - those are fixed
 * per authenticator TYPE, so they come from [NativeAuthenticatorDescriptor] (looked up by
 * [nativeToolId]) instead of being resent on every proof, exactly like an orchestrator tool's own
 * evidence is priced from its `ToolDescriptor` rather than resent on every outcome. [nativeToolId]
 * is Keycloak's own stable authenticator/execution-CONFIG id - fixed for that authenticator's
 * whole configuration. [amrSourceId] is the stable id of THIS specific proof/execution instance
 * (its "AmrUtils"-style bookkeeping of which native step established which factor, and for how
 * long it stays valid) - carried on every `amr` report for the SAME proof, including refreshes,
 * so `AuthEvidence.AmrRecord` can remember it and a later `UpdateAuthenticator` call for the same
 * execution is recognizable as a refresh, not a new proof.
 */
@Schema(description = "One native authenticator proof - which authenticator TYPE, and which specific execution/instance of it.")
data class AmrEntry(
    @field:Schema(example = "kc-otp-form") val nativeToolId: String,
    @field:Schema(example = "kc-otp-form-exec-1") val amrSourceId: String
)

/**
 * Everything the kc-facade's `OrchestratorAuthenticator` needs to hand a brand-new channel a
 * running start (docs/ideen/web-keycloak-kanal.md #6) - not just evidence, deliberately general:
 * whatever this channel accumulated that a LATER, unrelated `ChannelSession` row (a fresh flow
 * run, e.g. a step-up) should be able to resume from without re-proving it. [evidence] is the
 * REAL `AuthEvidence` this channel accumulated - not a hand-rolled, lossy duplicate of it (an
 * earlier version of this type used its own `List<AmrEntry>`; restoring through that shape instead
 * of the actual evidence type risked drifting out of sync with whatever `AuthEvidence` itself
 * grows next) - so a restored channel's evidence is exactly what the original channel had, not an
 * approximation of it.
 *
 * Fetched via its own explicit endpoint (`GET .../kc/channels/{channelSessionId}/restore-data`),
 * never bundled into the regular upsert response - the Authenticator's end-of-flow lifecycle hook
 * is the one and only caller, right when a `UserSessionModel` first becomes available to stash it
 * in a session note; every other response on the way there would carry a value nobody reads yet.
 * Kc-facade-only, same as everything else in this file - nothing outside `kc` needs it.
 *
 * Never travels on the wire as this plain shape - see [RestoreDataCodec], which signs it into an
 * opaque token bound to the UserSession it was minted for, so a leaked or reused note can't hand a
 * DIFFERENT UserSession evidence nobody actually proved under it.
 */
data class RestoreData(
    val accountId: Long? = null,
    val evidence: AuthEvidence? = null
)

/** Wire wrapper for `GET .../restore-data` (docs/ideen/web-keycloak-kanal.md #6) - a plain string response body would be an unusual shape next to the rest of this JSON API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The channel's current RestoreData, signed - null if there is nothing worth restoring yet.")
data class RestoreDataResponse(val restoreData: String? = null)
