package com.example.dpop.orchestrator.api.v1.tool

import com.example.dpop.orchestrator.api.v1.channel.ChannelService
import com.example.dpop.orchestrator.orchestration.Next
import com.example.dpop.orchestrator.orchestration.ToolOutcomeProcessor
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.api.v1.DpopBaseController
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
 * "Back"/"Switch": abandon the currently activated tool and return to its selection step, or -
 * when there is nothing left to switch to (no candidate concept for this category at all, or
 * an empty candidate list) - cancel the whole process instead.
 *
 * That fallback decision is deliberately NOT made here by checking the tool's category: a
 * category says what KIND of proof a tool gives, not whether there is a prior step in THIS
 * process to go back to. ToolOutcomeProcessor.reofferForCategory already owns the category ->
 * candidate-function mapping, so it also owns "is there anything to reoffer at all" - it
 * returns null for that, and this controller just reacts to null instead of re-deriving the
 * same knowledge from category a second time.
 *
 * Deliberately the ONE remaining generic, toolId-keyed endpoint in the tool namespace: unlike
 * activation/PATCH/read, this has no tool-specific behaviour at all - it never touches a
 * handler, only the catalog (for the tool's category) and process/channel state. Splitting it
 * into three per-tool controllers would just be the same implementation copy-pasted three
 * times, which is not what docs/08-projektrahmen.md A11 is asking for.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Abandoning an activated tool (Back/Switch)")
@SecurityRequirement(name = "dpop")
class ToolSwitchController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val toolRegistry: ToolHandlerRegistry,
    private val toolOutcomeProcessor: ToolOutcomeProcessor,
    private val controllerSupport: ToolControllerSupport,
    private val channelService: ChannelService,
    private val sessionManagementService: SessionManagementService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @DeleteMapping
    @Operation(
        summary = "Abandon this tool attempt",
        description = "Returns to the selection step for this tool's category, or - if nothing else could be offered - cancels the whole process."
    )
    fun switchAway(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @Parameter(hidden = true) @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ToolStateResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val context = controllerSupport.loadContext(toolSessionId, bindingKeyRef)
        controllerSupport.requireCurrentTool(context, toolId)
        // Invalidate immediately: a re-offered candidate can be the same toolId (today's
        // catalog has one method per category), so toolId matching alone can't tell the
        // abandoned session and a freshly re-activated one apart.
        sessionManagementService.expireToolSession(toolSessionId)

        val category = toolRegistry.descriptorOf(toolId).category
        val reoffer = toolOutcomeProcessor.reofferForCategory(category, context.processSession, context.channel)
        val outcome: Pair<Next, Map<String, Any?>?> = if (reoffer != null) {
            reoffer.next to reoffer.stepData
        } else {
            val channelId = context.channel.channelSessionId!!
            val cancelled = channelService.cancelActiveProcess(channelId, bindingKeyRef)
            (cancelled.next ?: Next.flow("registration", "selectIdentificationMethod")) to cancelled.stepData
        }

        return ResponseEntity.ok(ToolStateResponse(toolSessionId, outcome.second, outcome.first))
    }
}
