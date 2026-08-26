package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.journey.JourneyService
import com.example.dpop.orchestrator.session.SessionManagementService
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * "Back"/"Switch": abandon the currently activated tool. What happens then is entirely the
 * journey's decision - on a fallback state it moves along the chain, on a mandatory one it only
 * narrows what is left, and on the last thing this journey could offer it ends like a cancel.
 *
 * This controller deliberately makes none of that decision itself: whether there is anything to
 * go back to is a property of the current state, not of the tool's category.
 *
 * Deliberately the ONE generic, toolId-keyed endpoint in the tool namespace: unlike
 * activation/PATCH/read it has no tool-specific behaviour at all - it never touches a handler.
 * Splitting it per tool would be the same implementation copy-pasted, which is not what
 * docs/08-projektrahmen.md A11 asks for.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Abandoning an activated tool (Back/Switch)")
@SecurityRequirement(name = "dpop")
class ToolSwitchController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val toolRegistry: ToolHandlerRegistry,
    private val controllerSupport: ToolControllerSupport,
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val sessionManagementService: SessionManagementService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @DeleteMapping
    @Operation(
        summary = "Abandon this tool attempt",
        description = "Moves the journey on according to the state it is standing on - to the next fallback option, " +
            "back to the selection step, or to the end of the journey if nothing else could be offered."
    )
    fun switchAway(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, toolId)
        // Invalidate immediately: a re-offered candidate can be the same toolId, so toolId
        // matching alone can't tell the abandoned session and a freshly re-activated one apart.
        sessionManagementService.expireToolSession(toolSessionId)

        val step = journeyService.abandon(context.journey, context.channel, toolRegistry.descriptorOf(toolId))
        return ResponseEntity.ok(
            ChannelResponse(
                channel = channelService.buildChannelBlock(context.channel),
                next = step.next,
                stepData = step.stepData
            )
        )
    }
}
