package com.example.dpop.orchestrator.api.v1.identification.fsc

import com.example.dpop.orchestrator.api.v1.DpopBaseController
import com.example.dpop.orchestrator.api.v1.OrchestratorResponse
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
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

@RestController
@RequestMapping("/orchestrator/api/v1")
class FscIdentificationController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val fscService: FscIdentificationService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/app/channels/{channelSessionId}/identification-methods/fsc/attempts")
    fun startIdentificationFsc(
        @PathVariable channelSessionId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) data: Map<String, Any>?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = fscService.startIdentification(channelSessionId, bindingKeyRef, data)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PatchMapping("/identification-methods/fsc/attempts/{attemptId}")
    fun submitIdentificationDataFsc(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody data: Map<String, Any>,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = fscService.submitIdentificationData(attemptId, bindingKeyRef, data)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/identification-methods/fsc/attempts/{attemptId}")
    fun getIdentificationStatusFsc(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = fscService.getIdentificationStatus(attemptId, bindingKeyRef)
        return ResponseEntity.ok(response)
    }
}
