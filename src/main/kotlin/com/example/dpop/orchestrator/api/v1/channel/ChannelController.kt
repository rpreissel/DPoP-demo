package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

/** App-facing channel entry point and resume endpoint (docs/05-api.md #2). */
@RestController
@RequestMapping("/orchestrator/api/v1/app/channels")
@Tag(name = "App channels", description = "Orchestrator-first entry point for the App channel")
@SecurityRequirement(name = "dpop")
class ChannelController(
    private val channelService: ChannelService
) {

    @PostMapping
    @Operation(
        summary = "Create a new App channel",
        description = "Always mints a brand-new ChannelSession for this DPoP-bound device (docs/02-domaenenmodell.md " +
            "#3: the key proves the device, never a lookup key for resuming a session - use GET with a remembered " +
            "channelSessionId to resume). A device that was already registered still gets offered LOGIN, not a " +
            "fresh ident-fsc. To end a previous session first (logout), call DELETE on it before this."
    )
    fun createChannel(
        @BindingKey bindingKeyRef: String,
        @Valid @RequestBody request: ChannelCreateRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val response = channelService.initializeChannel(bindingKeyRef, request.requiredAcr, request.intent, request.availableTools)
        val location = uriBuilder.replacePath("/orchestrator/api/v1/app/channels/{channelSessionId}")
            .buildAndExpand(response.channel.channelSessionId).toUri()
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @GetMapping("/{channelSessionId}")
    @Operation(
        summary = "Read the current channel state",
        description = "The guaranteed resume entry point (docs/05-api.md #2): next always reflects the currently due step."
    )
    fun getChannel(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.getChannel(channelSessionId, bindingKeyRef))
    }

    @PostMapping("/{channelSessionId}/step-ups")
    @Operation(
        summary = "Raise the channel's required ACR floor",
        description = "The App channel's step-up trigger (docs/05-api.md #9). Only raises, never lowers."
    )
    fun raiseRequiredAcr(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody request: ChannelPatchRequest
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.raiseRequiredAcr(channelSessionId, bindingKeyRef, request.requiredAcr))
    }

    @DeleteMapping("/{channelSessionId}/process")
    @Operation(
        summary = "Cancel the current process",
        description = "Abandons the active REGISTRATION/LOGIN/STEP_UP process; the response already offers a fresh start where applicable."
    )
    fun cancel(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.cancelActiveProcess(channelSessionId, bindingKeyRef))
    }

    @DeleteMapping("/{channelSessionId}")
    @Operation(
        summary = "Log out",
        description = "Ends this channel for good (docs/02-domaenenmodell.md #3: AUTHENTICATED -> LOGGED_OUT, " +
            "terminal) - cancels any active process and discards the AuthContext. Never resumes on this " +
            "channelSessionId afterwards; call POST .../channels again for a new session (a known device still " +
            "skips straight to LOGIN there)."
    )
    fun logout(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<Void> {
        channelService.logout(channelSessionId, bindingKeyRef)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{channelSessionId}/answer")
    @Operation(
        summary = "Answer whatever the current step is waiting on",
        description = "Generic answer endpoint for a state that pauses for an explicit choice instead of a tool " +
            "run - today only the optional device-binding offer right after a lookup login " +
            "(next.step=offerDeviceBinding), answer=\"accept\"/\"decline\". Agreeing is the ONLY way such a login " +
            "ever makes this device recognizable for future logins - it never happens as a side effect, because " +
            "this intent is chosen precisely by people who want no device binding."
    )
    fun answer(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody request: AnswerRequest
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.answer(channelSessionId, bindingKeyRef, request.answer))
    }

    @GetMapping("/{channelSessionId}/methods")
    @Operation(
        summary = "Read the account's active authentication methods",
        description = "The methods collection as a real readable resource - identical data to ChannelResponse's " +
            "activeMethods (docs/05-api.md #2), just addressable on its own. Never includes fsc (identification " +
            "lives in identifications, not authenticationMethods). Empty list, not an error, when no account is " +
            "known yet for this channel."
    )
    fun getMethods(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<MethodsResponse> {
        return ResponseEntity.ok(channelService.getMethods(channelSessionId, bindingKeyRef))
    }

    @PostMapping("/{channelSessionId}/enrollments")
    @Operation(
        summary = "Voluntarily add another authentication method",
        description = "Channel must already be AUTHENTICATED. Offers AuthPolicy.enrollmentCandidates via the " +
            "existing enroll-* tools, unchanged; finishing does not require reaching any particular level - one " +
            "successful enrollment ends this and returns to AUTHENTICATED. Call again to add another."
    )
    fun manageMethods(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.startManageMethods(channelSessionId, bindingKeyRef))
    }

    @DeleteMapping("/{channelSessionId}/methods/{methodInstanceId}")
    @Operation(
        summary = "Deactivate an authentication method instance",
        description = "Channel must already be AUTHENTICATED and the instance currently active. Addressed by its " +
            "own id (GET .../methods), never by method name - a method can have several active instances (e.g. " +
            "multiple devices). Rejected (409) if removing it would drop the account below this channel's own " +
            "required level."
    )
    fun deactivateMethod(
        @PathVariable channelSessionId: UUID,
        @PathVariable methodInstanceId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.deactivateMethod(channelSessionId, bindingKeyRef, methodInstanceId))
    }

    @GetMapping("/{channelSessionId}/token")
    @Operation(
        summary = "Get the mock Keycloak AccessToken",
        description = "Covers both first issuance and refresh - call this again whenever a fresh token might be " +
            "needed. minValiditySeconds (default 15) is the caller's tolerance: if the current AccessToken still " +
            "has at least that much life left, it comes back unchanged; otherwise the backend mints a new one " +
            "(silently using the remembered RefreshToken where possible). The RefreshToken value itself is never " +
            "returned - it's a credential and stays server-side."
    )
    fun getToken(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestParam(defaultValue = "15") minValiditySeconds: Long
    ): ResponseEntity<TokenResponse> {
        return ResponseEntity.ok(channelService.getToken(channelSessionId, bindingKeyRef, minValiditySeconds))
    }

    @GetMapping("/{channelSessionId}/idclaims")
    @Operation(
        summary = "Get the fachliche ID-token claims",
        description = "Business-facing claims (accountId/personId/email/acr/amr/auth_time) - a separate resource " +
            "from the AccessToken's own claims, not encoded into it."
    )
    fun getIdClaims(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(channelService.getIdClaims(channelSessionId, bindingKeyRef))
    }
}
