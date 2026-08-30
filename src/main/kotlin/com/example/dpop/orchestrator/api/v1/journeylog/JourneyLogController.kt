package com.example.dpop.orchestrator.api.v1.journeylog

import com.example.dpop.orchestrator.journeylog.JourneyLogResponse
import com.example.dpop.orchestrator.journeylog.JourneyLogService
import com.example.dpop.tool_api.BindingKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Cross-channel by design (docs/04-orchestrierung.md): every journey ever run under the caller's
 * OWN binding key, regardless of which channelSessionId it belonged to - `bindingKeyRef` comes
 * from the validated DPoP proof itself ([BindingKey]), so it is never a value the caller can
 * choose; no separate authorization check is needed beyond that resolution.
 */
@RestController
@RequestMapping("/orchestrator/api/v1/journey-log")
@Tag(name = "Journey log", description = "Reichhaltiges, nach bindingKeyRef abrufbares Journey-Log (Debug/Demo, kein Audit-Trail)")
@SecurityRequirement(name = "dpop")
class JourneyLogController(
    private val journeyLogService: JourneyLogService
) {

    @GetMapping
    @Operation(
        summary = "Read this device's journey log",
        description = "Every journey step ever recorded under the caller's own bindingKeyRef, newest first - across all channelSessionIds this device ever created."
    )
    fun getJourneyLog(@BindingKey bindingKeyRef: String): ResponseEntity<JourneyLogResponse> =
        ResponseEntity.ok(journeyLogService.getLogFor(bindingKeyRef))
}
