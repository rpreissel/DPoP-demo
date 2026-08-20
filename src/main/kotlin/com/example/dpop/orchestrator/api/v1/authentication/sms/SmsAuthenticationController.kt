package com.example.dpop.orchestrator.api.v1.authentication.sms

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
class SmsAuthenticationController(
    dpopValidator: DpopValidator,
    jwkThumbprintService: JwkThumbprintService,
    private val smsService: SmsAuthenticationService
) : DpopBaseController(dpopValidator, jwkThumbprintService) {

    @PostMapping("/app/channels/{channelSessionId}/authentication-methods/sms/enroll/attempts")
    fun startAuthenticationSmsEnroll(
        @PathVariable channelSessionId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) data: Map<String, Any>?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.startEnroll(channelSessionId, bindingKeyRef)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/app/channels/{channelSessionId}/authentication-methods/sms/use/attempts")
    fun startAuthenticationSmsUse(
        @PathVariable channelSessionId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody(required = false) data: Map<String, Any>?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.startUse(channelSessionId, bindingKeyRef)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PatchMapping("/authentication-methods/sms/enroll/attempts/{attemptId}")
    fun submitAuthenticationDataSmsEnroll(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody data: Map<String, Any>,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.submitEnroll(attemptId, bindingKeyRef, data)
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/authentication-methods/sms/use/attempts/{attemptId}")
    fun submitAuthenticationDataSmsUse(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        @RequestBody data: Map<String, Any>,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.submitUse(attemptId, bindingKeyRef, data)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/authentication-methods/sms/enroll/attempts/{attemptId}")
    fun getAuthenticationStatusSmsEnroll(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.getStatus(attemptId, bindingKeyRef)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/authentication-methods/sms/use/attempts/{attemptId}")
    fun getAuthenticationStatusSmsUse(
        @PathVariable attemptId: UUID,
        @RequestHeader("DPoP") dpopProof: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<OrchestratorResponse> {
        val bindingKeyRef = validateAndExtractBindingKeyRef(dpopProof, httpRequest)
        val response = smsService.getStatus(attemptId, bindingKeyRef)
        return ResponseEntity.ok(response)
    }
}
