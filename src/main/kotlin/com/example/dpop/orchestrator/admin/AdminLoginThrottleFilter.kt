package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.session.AttemptCounter
import com.example.dpop.orchestrator.session.ThrottleScope
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.Base64

/**
 * The operator login is guessed like any other password (review 2026-09-26, F-7): after
 * [MAX_FAILURES] wrong passwords for one user name, requests under that name are refused with 429
 * for [LOCKOUT] - the right password included, so the lock reveals nothing about it. Keyed by the
 * name as sent, known or not; a success resets the counter. Runs before HTTP Basic in the admin
 * chain only.
 */
class AdminLoginThrottleFilter(private val counter: AttemptCounter) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val user = basicUserOf(request)
        if (user == null) {
            chain.doFilter(request, response)
            return
        }
        if (counter.isLocked(ThrottleScope.ADMIN, user)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            return
        }
        chain.doFilter(request, response)
        when (response.status) {
            HttpStatus.UNAUTHORIZED.value() -> counter.recordFailure(ThrottleScope.ADMIN, user, MAX_FAILURES, LOCKOUT)
            in 200..399 -> counter.reset(ThrottleScope.ADMIN, user)
        }
    }

    private fun basicUserOf(request: HttpServletRequest): String? {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        if (!header.startsWith("Basic ", ignoreCase = true)) return null
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return null
        return decoded.substringBefore(':').trim().lowercase().take(128).ifEmpty { null }
    }

    private companion object {
        const val MAX_FAILURES = 5
        val LOCKOUT: Duration = Duration.ofMinutes(15)
    }
}
