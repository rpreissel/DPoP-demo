package com.example.dpop.orchestrator.api.v1.kc

import com.example.dpop.orchestrator.kc.PeerAuthValidationException
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.buildRequestUrl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The kc-facade's one facade-specific endpoint (docs/ideen/web-keycloak-kanal.md #6) - everything
 * afterwards runs over the same facade-neutral tool endpoints the App channel uses. Proof-of-caller
 * is a signed Keycloak peer-auth assertion in `Authorization`, never DPoP.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/kc/channels")
@Tag(name = "KC channels", description = "The kc facade's one facade-specific endpoint - upsert channel + advance journey")
@SecurityRequirement(name = "kc-peer-auth")
class KcChannelController(
    private val peerAuthValidator: PeerAuthValidator,
    private val kcChannelService: KcChannelService
) {

    @PatchMapping("/{channelSessionId}")
    @Operation(
        summary = "Upsert a kc channel and advance its journey",
        description = "Upsert semantics (docs/ideen/web-keycloak-kanal.md #6): creates the channel " +
            "on first call under this Keycloak-chosen id, just resumes it on every later one - " +
            "idempotent by construction, no separate create-then-resume round trip. " +
            "accountId/targetAcr are only meaningful on step-up.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "First call for a fresh Keycloak flow run - offers every kc-usable method.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"options": ["ident-fsc", "auth-password"]}
                    }
                """)])]
            )
        ]
    )
    fun upsertChannel(
        @PathVariable channelSessionId: UUID,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody(required = false) request: KcChannelUpsertRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val assertion = peerAuthValidator.validate(
            bearerToken(authorization),
            httpRequest.method,
            buildRequestUrl(httpRequest)
        )
        val body = request ?: KcChannelUpsertRequest()
        val response = kcChannelService.upsertChannel(
            channelSessionId, assertion, body.accountId, body.targetAcr, body.amr, body.restoreData, body.kcSessionId, body.availableTools
        )
        return ResponseEntity.ok(response)
    }

    @GetMapping("/{channelSessionId}/restore-data")
    @Operation(
        summary = "Fetch this channel's signed RestoreData",
        description = "For the Authenticator's end-of-flow lifecycle hook only (docs/ideen/" +
            "web-keycloak-kanal.md #6) - reads back what this channel accumulated, to stash in a " +
            "Keycloak UserSessionModel note and resubmit at a later flow's start. kcSessionId is " +
            "passed explicitly (not read off the channel's own anchor) because the returned token " +
            "must remain valid across the flow-run boundary the channel itself does not survive - " +
            "a fresh channel next flow run, but the very same UserSessionModel id."
    )
    fun getRestoreData(
        @PathVariable channelSessionId: UUID,
        @RequestParam kcSessionId: String,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<RestoreDataResponse> {
        val assertion = peerAuthValidator.validate(
            bearerToken(authorization),
            httpRequest.method,
            buildRequestUrl(httpRequest)
        )
        return ResponseEntity.ok(RestoreDataResponse(kcChannelService.restoreData(channelSessionId, assertion, kcSessionId)))
    }

    private fun bearerToken(authorization: String?): String {
        val value = authorization?.trim()
            ?: throw PeerAuthValidationException("Missing Authorization header")
        return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).trim() else value
    }
}
