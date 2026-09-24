package com.example.dpop.orchestrator.channel

import com.example.dpop.orchestrator.policy.AuthEvidence
import com.example.dpop.tool_api.ActiveMethodView
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/*
 * The response shapes the channel services build - next to the services rather than in `api.v1`.
 *
 * Same reasoning as `ChannelResponse` in `tool_api`: there is one global API version (docs/05-api.md,
 * Versionierung), so a response shape is not a v1 thing. What IS v1 is how requests reach these
 * services - routes, request bodies, parameter binding - and that stays in `api.v1`. If a v2 ever
 * changes one of these shapes, that version gets its own type and maps to it.
 */

@Schema(description = "The account's active authentication methods (docs/05-api.md #2). Never contains fsc.")
data class MethodsResponse(
    val methods: List<ActiveMethodView>
)

@Schema(
    description = "Mock Keycloak AccessToken (a spec-shaped unsecured JWT, alg=none - parse and " +
        "display its payload, no verification needed) plus both token lifetimes. The RefreshToken " +
        "value itself is deliberately never part of this response - it's a credential and stays " +
        "server-side; refreshExpiresAt is the only thing about it exposed."
)
data class TokenResponse(
    @field:Schema(example = "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiIsImFjciI6ImxvYTIiLCJhbXIiOlsic21zIl19.")
    val accessToken: String,
    val tokenType: String = "Bearer",
    @field:Schema(example = "2026-08-28T18:05:00Z")
    val accessExpiresAt: Instant,
    @field:Schema(example = "2026-08-29T18:00:00Z")
    val refreshExpiresAt: Instant
)

@Schema(
    description = "Whether this device's DPoP key is already linked to an account (DeviceAccountLink, " +
        "docs/02-domaenenmodell.md #1) - a pure read, no channel/journey created. Lets the entry screen show " +
        "\"this device belongs to X\" before the user picks how to start."
)
/** One key-bound credential living on the calling device - see [DeviceLinkResponse.boundCredentials]. */
data class BoundCredentialView(
    @field:Schema(example = "kobil") val method: String,
    @field:Schema(example = "dev-1a2b3c4d5e6f") val reference: String
)

data class DeviceLinkResponse(
    val linked: Boolean,
    @field:Schema(example = "42")
    val accountId: Long? = null,
    @field:Schema(example = "Max Muster", description = "Demo-only, like ID-Token-Claims' name (docs/05-api.md) - who this device is linked to.")
    val personName: String? = null,
    @field:Schema(
        description = "Demo-only: what else this device is known by - one entry per key-bound " +
            "credential of the linked account living on THIS key, with the reference its own " +
            "method discloses (docs/09-dpop.md). The `device` method names its credential key, " +
            "`kobil` the identifier the provider gave this phone. Absence is meaningful: a client " +
            "that holds local data for a method no longer listed here is holding something stale."
    )
    val boundCredentials: List<BoundCredentialView> = emptyList()
)

/**
 * One method a native Keycloak authenticator just proved THIS flow run (docs/05-api.md,
 * Abschnitt 3; ADR-8). Deliberately just two ids, not method/loa/factorTypes too - those are fixed
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
 * running start (docs/05-api.md Abschnitt 3) - not just evidence, deliberately general:
 * whatever this channel accumulated that a LATER, unrelated `ChannelSession` row (a fresh flow
 * run, e.g. a step-up) should be able to resume from without re-proving it. [evidence] is the
 * REAL `AuthEvidence` this channel accumulated, not a hand-rolled duplicate of it: restoring
 * through a lossy shape of its own would drift out of sync with whatever `AuthEvidence` grows
 * next, so a restored channel's evidence is exactly what the original had.
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
