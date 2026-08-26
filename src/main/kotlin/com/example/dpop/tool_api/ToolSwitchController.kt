package com.example.dpop.tool_api

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * "Back"/"Switch": abandon the currently activated tool. What happens then is entirely the
 * journey's decision - on a fallback state it moves along the chain, on a mandatory one it only
 * narrows what is left, and on the last thing this journey could offer it ends like a cancel.
 * This controller makes none of that decision itself: whether there is anything to go back to is
 * a property of the current state, not of the tool's category.
 *
 * Lives here rather than in the orchestrator, and is the reason [ToolEndpoint] carries [abandon]
 * as its own method rather than leaving this controller to reach for orchestrator internals: once
 * that one effect is behind the SPI, this controller needs nothing else - no handler, no
 * orchestrator type, not even DPoP validation directly (`@BindingKey` resolves it). It ships once,
 * here, and every method module gets Back/Switch for free instead of reimplementing it
 * (docs/08-projektrahmen.md A11: this is the one truly generic, toolId-keyed case, not a
 * precedent for per-tool dispatch).
 */
@RestController
@RequestMapping("/orchestrator/api/v1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Abandoning an activated tool (Back/Switch)")
@SecurityRequirement(name = "dpop")
class ToolSwitchController(private val toolEndpoint: ToolEndpoint) {

    @DeleteMapping
    @Operation(
        summary = "Abandon this tool attempt",
        description = "Moves the journey on according to the state it is standing on - to the next fallback option, " +
            "back to the selection step, or to the end of the journey if nothing else could be offered."
    )
    fun switchAway(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, toolId)
        toolEndpoint.requireCurrentTool(context)
        return ResponseEntity.ok(toolEndpoint.abandon(context))
    }
}
