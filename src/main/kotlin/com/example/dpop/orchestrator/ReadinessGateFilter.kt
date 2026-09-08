package com.example.dpop.orchestrator

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Antwortet mit 503 statt den Request durchzureichen, solange [ReadinessState.isReady] false ist
 * (siehe dort - v.a. das keycloak-Profil-Fenster waehrend laufender Migrationen). Ohne dieses Gate
 * bekamen frueh eintreffende Requests einen wenig aussagekraeftigen 500er, z.B. "invalid_client",
 * weil der orchestrator-admin-Client zu dem Zeitpunkt noch nicht existierte.
 */
@Component
class ReadinessGateFilter(private val readinessState: ReadinessState) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!readinessState.isReady) {
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.contentType = "application/json"
            response.writer.write(
                """{"error":"starting_up","error_description":"Orchestrator startet noch (Keycloak-Migrationen laufen)"}"""
            )
            return
        }
        filterChain.doFilter(request, response)
    }
}
