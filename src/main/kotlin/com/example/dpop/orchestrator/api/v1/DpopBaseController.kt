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

    private fun buildRequestUrl(request: HttpServletRequest): String {
        val scheme = request.scheme
        val host = request.serverName
        val port = request.serverPort
        val path = request.requestURI

        val url = StringBuilder(scheme).append("://").append(host)
        if (("http" == scheme && port != 80) || ("https" == scheme && port != 443)) {
            url.append(":").append(port)
        }
        url.append(path)
        return url.toString()
    }

    @ExceptionHandler(DpopValidationException::class)
    fun handleDpopValidation(e: DpopValidationException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to (e.message ?: "")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "")))
}
