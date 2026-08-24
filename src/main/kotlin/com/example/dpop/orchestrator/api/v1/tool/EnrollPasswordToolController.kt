package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_password.EnrollPasswordToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.tool_spi.ToolCategory
import com.example.dpop.tool_spi.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val ENROLL_PASSWORD_TOOL_ID = "enroll-password"

data class EnrollPasswordPatchRequest(
    val password: String? = null
)

/**
 * toolId=enroll-password. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: enroll-password", description = "Registers a username/password credential as a knowledge factor")
@SecurityRequirement(name = "dpop")
class EnrollPasswordToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: EnrollPasswordToolHandler,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/enroll-password")
    @Operation(summary = "Activate enroll-password", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, ENROLL_PASSWORD_TOOL_ID, ToolCategory.ENROLL)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        return ResponseEntity.status(HttpStatus.CREATED).body(controllerSupport.applyOutcome(ENROLL_PASSWORD_TOOL_ID, outcome, context))
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-password")
    @Operation(summary = "Supply the password", description = "The credential is self-verifying, no separate confirmation step.")
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: EnrollPasswordPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, ENROLL_PASSWORD_TOOL_ID)

        val body = request ?: EnrollPasswordPatchRequest()
        val outcome = handler.patch(toolSessionId, body.password)

        return ResponseEntity.ok(controllerSupport.applyOutcome(ENROLL_PASSWORD_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-password")
    @Operation(summary = "Read the current enroll-password state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, ENROLL_PASSWORD_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, ENROLL_PASSWORD_TOOL_ID, context, outcome))
    }
}
