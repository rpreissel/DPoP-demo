package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.id_fsc.IdentFscToolHandler
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.channel.ChannelResponse
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
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

private const val IDENT_FSC_TOOL_ID = "ident-fsc"

data class IdentFscPatchRequest(
    val kvnr: String? = null,
    val name: String? = null,
    val vorname: String? = null,
    val fsc: String? = null
)

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: ident-fsc", description = "KVNR/name/vorname/FSC identification")
@SecurityRequirement(name = "dpop")
class IdentFscToolController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val handler: IdentFscToolHandler,
    private val extStammdatenService: ExtStammdatenService,
    private val controllerSupport: ToolControllerSupport
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/orchestrator/api/v1/app/channels/{channelSessionId}/tools/ident-fsc")
    @Operation(summary = "Activate ident-fsc", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.beginActivation(channelSessionId, bindingKeyRef, IDENT_FSC_TOOL_ID, ToolCategory.IDENT)
        val outcome = handler.start(context.toolSession.toolSessionId!!)
        val response = controllerSupport.applyOutcome(IDENT_FSC_TOOL_ID, outcome, context)
        val location = controllerSupport.activationLocation(uriBuilder, context.toolSession.toolSessionId!!, IDENT_FSC_TOOL_ID)
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-fsc")
    @Operation(
        summary = "Supply KVNR/name/vorname/FSC",
        description = "Only the fields being supplied or corrected need to be sent; all four together also resolves in one call."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: IdentFscPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, IDENT_FSC_TOOL_ID)

        val body = request ?: IdentFscPatchRequest()
        // Only the orchestrator may reference both id_fsc and ext_stammdaten (docs/08-projektrahmen.md #3).
        val personId = body.kvnr?.let { extStammdatenService.findPersonIdByKvnr(it) }
        val outcome = handler.patch(toolSessionId, body.kvnr, body.name, body.vorname, body.fsc, personId)

        return ResponseEntity.ok(controllerSupport.applyOutcome(IDENT_FSC_TOOL_ID, outcome, context))
    }

    @GetMapping("/orchestrator/api/v1/tools/{toolSessionId}/ident-fsc")
    @Operation(summary = "Read the current ident-fsc state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        val outcome = if (controllerSupport.isCurrentTool(context, IDENT_FSC_TOOL_ID)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(controllerSupport.buildReadResponse(toolSessionId, IDENT_FSC_TOOL_ID, context, outcome))
    }
}
