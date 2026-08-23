package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.auth_sms.EnrollSmsToolHandler
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

private const val ENROLL_SMS_TOOL_ID = "enroll-sms"

data class EnrollSmsPatchRequest(
    val phoneNumber: String? = null,
    val tan: String? = null
)

/**
 * toolId=enroll-sms (docs/06-ablaeufe.md #4). One controller owns activation, PATCH and GET
 * for this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: enroll-sms", description = "Registers a new phone number as a 2nd factor")
@SecurityRequirement(name = "dpop")
class EnrollSmsToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: EnrollSmsToolHandler,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tool-activate/enroll-sms")
    @Operation(summary = "Activate enroll-sms", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, ENROLL_SMS_TOOL_ID, ToolCategory.ENROLL)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        return ResponseEntity.status(HttpStatus.CREATED).body(controllerSupport.applyOutcome(ENROLL_SMS_TOOL_ID, outcome, context))
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-sms")
    @Operation(
        summary = "Supply phone number, then TAN",
        description = "First call with phoneNumber triggers the TAN send; a second call with tan confirms it."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: EnrollSmsPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, ENROLL_SMS_TOOL_ID)

        val body = request ?: EnrollSmsPatchRequest()
        val outcome = handler.patch(toolSessionId, body.phoneNumber, body.tan)

        return ResponseEntity.ok(controllerSupport.applyOutcome(ENROLL_SMS_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/enroll-sms")
    @Operation(summary = "Read the current enroll-sms state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, ENROLL_SMS_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, ENROLL_SMS_TOOL_ID, context, outcome))
    }
}
