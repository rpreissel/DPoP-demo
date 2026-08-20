package com.example.dpop.orchestrator.api.v1.channel

import com.example.dpop.orchestrator.api.v1.ChannelSessionRequest
import com.example.dpop.orchestrator.api.v1.ChannelSessionResponse
import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import com.example.dpop.orchestrator.session.ChannelSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/orchestrator/api/v1")
class ChannelController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val channelService: ChannelService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/app/channels")
    fun createChannel(
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: ChannelSessionRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val channel = if (request != null && "WEB".equals(request.channel, ignoreCase = true))
            ChannelSession.Channel.WEB
        else
            ChannelSession.Channel.APP

        val response = channelService.initializeFlow(bindingKeyRef, channel)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/app/channels/{channelSessionId}")
    fun getChannel(
        @PathVariable channelSessionId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelSessionResponse> {
        validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = channelService.getChannelSession(channelSessionId)
        return ResponseEntity.ok(response)
    }

    // Legacy path (kept for backward compatibility)
    @PostMapping("/channel")
    fun initializeChannel(
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) request: ChannelSessionRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val channel = if (request != null && "WEB".equals(request.channel, ignoreCase = true))
            ChannelSession.Channel.WEB
        else
            ChannelSession.Channel.APP

        val response = channelService.initializeFlow(bindingKeyRef, channel)
        return ResponseEntity.ok(response)
    }
}
