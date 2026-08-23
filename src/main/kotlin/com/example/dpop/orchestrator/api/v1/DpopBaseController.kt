package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.dpop.DpopProof
import com.example.dpop.orchestrator.dpop.DpopValidationException
import com.example.dpop.orchestrator.dpop.DpopValidator
import com.example.dpop.orchestrator.dpop.JwkThumbprintService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler

abstract class DpopBaseController(
    private val dpopValidator: DpopValidator,
    private val jwkThumbprintService: JwkThumbprintService
) {

    protected fun validateAndExtractBindingKeyRef(dpopProof: String, request: HttpServletRequest): String {
        val requestUrl = buildRequestUrl(request)
        val proof = dpopValidator.validate(dpopProof, request.method, requestUrl)
        return jwkThumbprintService.computeThumbprint(proof.publicKey)
    }

    private fun buildRequestUrl(request: HttpServletRequest): String = buildString {
        append(request.scheme).append("://").append(request.serverName)
        val port = request.serverPort
        val scheme = request.scheme
        if ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) {
            append(":").append(port)
        }
        append(request.requestURI)
    }

    /** Missing/invalid DPoP - docs/07-betrieb.md #1: 401. */
    @ExceptionHandler(DpopValidationException::class)
    fun handleDpopValidation(e: DpopValidationException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "UNAUTHORIZED", "message" to (e.message ?: "")))
}
