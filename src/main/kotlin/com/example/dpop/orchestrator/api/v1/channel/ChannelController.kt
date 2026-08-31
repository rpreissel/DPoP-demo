package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
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

/**
 * The one facade-specific App endpoint (docs/05-api.md #2, bd DPoP-demo-bqi.5): everything else a
 * channel offers lives on the facade-neutral [ChannelController] below, shared with the future
 * kc-facade's own `POST /kc/channels`. Only creation differs per facade - it's where each facade's
 * own proof-of-caller happens (DPoP proof here; a signed Keycloak assertion for `/kc/channels`).
 */
@RestController
@RequestMapping("/orchestrator/api/v1/app/channels")
@Tag(name = "App channels", description = "The App facade's one facade-specific endpoint - channel creation")
@SecurityRequirement(name = "dpop")
class ChannelCreationController(
    private val channelService: ChannelService
) {

    @PostMapping
    @Operation(
        summary = "Create a new App channel",
        description = "Always mints a brand-new ChannelSession for this DPoP-bound device (docs/02-domaenenmodell.md " +
            "#3: the key proves the device, never a lookup key for resuming a session - use GET with a remembered " +
            "channelSessionId to resume). A device that was already registered still gets offered LOGIN, not a " +
            "fresh ident-fsc. To end a previous session first (logout), call DELETE on it before this.",
        responses = [
            ApiResponse(
                responseCode = "201",
                description = "New channel - an unrecognized device lands on the identification choice.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "registration", "step": "selectIdentificationMethod"},
                      "stepData": {"options": ["ident-fsc", "ident-eid"]}
                    }
                """)])]
            )
        ]
    )
    fun createChannel(
        @BindingKey bindingKeyRef: String,
        @Valid @RequestBody request: ChannelCreateRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val response = channelService.initializeChannel(bindingKeyRef, request.requiredAcr, request.intent, request.availableTools)
        val location = uriBuilder.replacePath("/orchestrator/api/v1/channels/{channelSessionId}")
            .buildAndExpand(response.channel.channelSessionId).toUri()
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }
}

/**
 * Facade-neutral channel resource (docs/05-api.md #2, bd DPoP-demo-bqi.5) - everything a channel
 * offers once it exists, addressed the same way regardless of which facade created it (today only
 * the App facade does; the planned kc-facade's `POST /kc/channels` will mint the same resource).
 * No `/app/` or `/kc/` prefix here on purpose - see [ChannelCreationController] for the one
 * endpoint that IS facade-specific.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/channels")
@Tag(name = "Channels", description = "Facade-neutral channel resource - shared by every facade that creates one")
@SecurityRequirement(name = "dpop")
class ChannelController(
    private val channelService: ChannelService
) {

    @GetMapping("/{channelSessionId}")
    @Operation(
        summary = "Read the current channel state",
        description = "The guaranteed resume entry point (docs/05-api.md #2): next always reflects the currently due step.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Resumed mid-step-up, waiting on an SMS TAN.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "tool", "toolId": "auth-sms", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
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
        description = "The App channel's step-up trigger (docs/05-api.md #9). Only raises, never lowers.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "loa3 requested, current evidence (loa2) doesn't satisfy it - offers the candidate methods.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa2"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"options": ["auth-sms", "auth-password", "auth-device"]}
                    }
                """)])]
            )
        ]
    )
    fun raiseRequiredAcr(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody request: ChannelPatchRequest
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.raiseRequiredAcr(channelSessionId, bindingKeyRef, request.requiredAcr))
    }

    @DeleteMapping("/{channelSessionId}/journey")
    @Operation(
        summary = "Cancel the current journey",
        description = "Abandons the currently running AuthJourney, whatever intent started it; the response already offers a fresh start where applicable.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A cancelled REGISTER journey restarts the same entry intent from scratch.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "ident-fsc", "step": "input"}
                    }
                """)])]
            )
        ]
    )
    fun cancel(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.cancelActiveJourney(channelSessionId, bindingKeyRef))
    }

    @PostMapping("/{channelSessionId}/logouts")
    @Operation(
        summary = "Start a confirmed logout",
        description = "Channel must already be AUTHENTICATED. Starts a LOGOUT journey with a confirmation " +
            "prompt (next.context=prompt, next.step=confirm, stepData.prompt). On accept (via POST .../answer) " +
            "the channel ends for good (AUTHENTICATED -> LOGGED_OUT, terminal). On decline the channel stays " +
            "AUTHENTICATED. Analogous to POST .../account-deletions.",
        responses = [ApiResponse(
            responseCode = "200",
            description = "Confirmation prompt for the logout."
        )]
    )
    fun startLogout(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.startLogout(channelSessionId, bindingKeyRef))
    }

    @DeleteMapping("/{channelSessionId}")
    @Operation(
        summary = "Log out (direct, no confirmation)",
        description = "Ends this channel for good (docs/02-domaenenmodell.md #3: AUTHENTICATED -> LOGGED_OUT, " +
            "terminal) - cancels any active process and discards the AuthContext. For interactive clients " +
            "prefer POST .../logouts which shows a confirmation prompt first. Never resumes on this " +
            "channelSessionId afterwards; call POST .../channels again for a new session (a known device still " +
            "skips straight to LOGIN there).",
        responses = [ApiResponse(responseCode = "204", description = "Logged out - no body.")]
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
        description = "Generic answer endpoint for any state that pauses for an explicit yes/no instead of a tool " +
            "run (next.context=prompt, next.step=confirm, stepData.prompt) - the optional device-binding offer " +
            "right after a lookup login, the account-deletion confirmation, and any future one alike, all through " +
            "this same address, answer=\"accept\"/\"decline\". For device binding specifically: agreeing is the " +
            "ONLY way such a login ever makes this device recognizable for future logins - it never happens as a " +
            "side effect, because this intent is chosen precisely by people who want no device binding.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Device binding accepted after a lookup login - journey settles into authenticated.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
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
            "known yet for this channel.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "methods": [
                        {"id": "7f3e2b1a-0c9d-4e8f-8a1b-2c3d4e5f6a7b", "method": "sms"},
                        {"id": "b2d4f6a8-1c3e-4a5b-9d7f-8e0a1b2c3d4e", "method": "device", "label": "Laptop"}
                      ]
                    }
                """)])]
            )
        ]
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
            "successful enrollment ends this and returns to AUTHENTICATED. Call again to add another.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Already AUTHENTICATED with sms+password - offered the still-missing methods.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["sms", "password"]},
                      "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                      "stepData": {"options": ["enroll-device", "enroll-email"]}
                    }
                """)])]
            )
        ]
    )
    fun manageMethods(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.startManageMethods(channelSessionId, bindingKeyRef))
    }

    @PostMapping("/{channelSessionId}/account-deletions")
    @Operation(
        summary = "Delete the account of an already authenticated channel",
        description = "Channel must already be AUTHENTICATED. Starts an unconditional yes/no confirmation " +
            "(next.context=prompt, next.step=confirm, stepData.prompt - the same generic address every " +
            "AnswerableState uses) that always comes FIRST, before anything else is checked. Only once accepted " +
            "does the loa2 gate apply (a step-up first if not yet reached); if that gate needed a step-up, its own " +
            "fresh proof is enough and deletion follows immediately, otherwise one more fresh re-proof of any " +
            "active factor is required - never skipped just because the session already carried loa2. On success " +
            "the account and everything it owns is hard-deleted, every ChannelSession it was ever bound to is " +
            "logged out server-side, and this response's channel.state is LOGGED_OUT with no next, exactly like " +
            "a plain logout.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Confirmation prompt for the account deletion.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED"},
                      "next": {"type": "orchestrator", "context": "prompt", "step": "confirm"},
                      "stepData": {"prompt": {"@t": "Confirm", "title": "Account wirklich löschen?", "confirmLabel": "Account löschen", "cancelLabel": "Abbrechen", "destructive": true}}
                    }
                """)])]
            )
        ]
    )
    fun deleteAccount(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        return ResponseEntity.ok(channelService.startDeleteAccount(channelSessionId, bindingKeyRef))
    }

    @DeleteMapping("/{channelSessionId}/methods/{methodInstanceId}")
    @Operation(
        summary = "Deactivate an authentication method instance",
        description = "Channel must already be AUTHENTICATED and the instance currently active. Addressed by its " +
            "own id (GET .../methods), never by method name - a method can have several active instances (e.g. " +
            "multiple devices). Rejected (409) if removing it would drop the account below this channel's own " +
            "required level.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The sms method instance was removed - password alone still satisfies loa1.",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "channel": {
                        "channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED",
                        "currentAcr": "loa1", "currentAmr": ["password"],
                        "activeMethods": [{"id": "7f3e2b1a-0c9d-4e8f-8a1b-2c3d4e5f6a7b", "method": "password"}]
                      },
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
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
            "returned - it's a credential and stays server-side.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "accessToken": "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiIsImFjciI6ImxvYTIiLCJhbXIiOlsic21zIl19.",
                      "tokenType": "Bearer",
                      "accessExpiresAt": "2026-08-28T18:05:00Z",
                      "refreshExpiresAt": "2026-08-29T18:00:00Z"
                    }
                """)])]
            )
        ]
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
            "from the AccessToken's own claims, not encoded into it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(examples = [ExampleObject(value = """
                    {
                      "accountId": 42,
                      "personId": 7,
                      "email": "max.mustermann@example.com",
                      "acr": "loa2",
                      "amr": ["sms", "password"],
                      "auth_time": 1798567890
                    }
                """)])]
            )
        ]
    )
    fun getIdClaims(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(channelService.getIdClaims(channelSessionId, bindingKeyRef))
    }
}
