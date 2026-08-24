package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** App-facing channel entry point and resume endpoint (docs/05-api.md #2). */
@RestController
@RequestMapping("/orchestrator/api/v1/app/channels")
@Tag(name = "App channels", description = "Orchestrator-first entry point for the App channel")
@SecurityRequirement(name = "dpop")
class ChannelController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val channelService: ChannelService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping
    @Operation(
        summary = "Create a new App channel",
        description = "Always mints a brand-new ChannelSession for this DPoP-bound device (docs/02-domaenenmodell.md " +
            "#3: the key proves the device, never a lookup key for resuming a session - use GET with a remembered " +
            "channelSessionId to resume). A device that was already registered still gets offered LOGIN, not a " +
            "fresh ident-fsc. To end a previous session first (logout), call DELETE on it before this."
    )
    fun createChannel(
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: ChannelCreateRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.initializeChannel(bindingKeyRef, request?.requiredAcr, request?.intent))
    }

    @GetMapping("/{channelSessionId}")
    @Operation(
        summary = "Read the current channel state",
        description = "The guaranteed resume entry point (docs/05-api.md #2): next always reflects the currently due step."
    )
    fun getChannel(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.getChannel(channelSessionId, bindingKeyRef))
    }

    @PatchMapping("/{channelSessionId}")
    @Operation(
        summary = "Raise the channel's required ACR floor",
        description = "The App channel's step-up trigger (docs/05-api.md #9). Only raises, never lowers."
    )
    fun raiseRequiredAcr(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody request: ChannelPatchRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.raiseRequiredAcr(channelSessionId, bindingKeyRef, request.requiredAcr))
    }

    @PostMapping("/{channelSessionId}/cancel")
    @Operation(
        summary = "Cancel the current process",
        description = "Abandons the active REGISTRATION/LOGIN/STEP_UP process; the response already offers a fresh start where applicable."
    )
    fun cancel(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
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
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        channelService.logout(channelSessionId, bindingKeyRef)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{channelSessionId}/methods")
    @Operation(
        summary = "Voluntarily add another authentication method",
        description = "Channel must already be AUTHENTICATED. Offers AuthPolicy.enrollmentCandidates via the " +
            "existing enroll-* tools, unchanged; finishing does not require reaching any particular level - one " +
            "successful enrollment ends this and returns to AUTHENTICATED. Call again to add another."
    )
    fun manageMethods(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.startManageMethods(channelSessionId, bindingKeyRef))
    }

    @DeleteMapping("/{channelSessionId}/methods/{method}")
    @Operation(
        summary = "Deactivate an authentication method",
        description = "Channel must already be AUTHENTICATED and the method currently active. Rejected (409) if " +
            "removing it would drop the account below this channel's own required level."
    )
    fun deactivateMethod(
        @PathVariable channelSessionId: UUID,
        @PathVariable method: String,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.deactivateMethod(channelSessionId, bindingKeyRef, method))
    }
}
