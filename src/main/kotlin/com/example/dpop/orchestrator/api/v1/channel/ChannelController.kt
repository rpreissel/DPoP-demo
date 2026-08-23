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
        summary = "Create or resume the App channel",
        description = "Resolves the ChannelSession by the DPoP-bound binding key and returns the first (or current) fällig next step."
    )
    fun createChannel(
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: ChannelCreateRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        return ResponseEntity.ok(channelService.initializeChannel(bindingKeyRef, request?.requiredAcr))
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
}
