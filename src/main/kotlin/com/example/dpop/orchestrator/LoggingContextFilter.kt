package com.example.dpop.orchestrator

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Puts what a log line belongs to into the MDC (review 2026-09-26, B-9): a request id for every
 * request, and the channel or tool session the path names. Every line written while the request
 * runs carries them - in the console through `logging.pattern.correlation`, in structured (ECS)
 * output as fields - so one user's journey can be followed across lines without knowing which
 * class logged what.
 *
 * Only ids that are already in the URL; nothing personal, no account data. First in the chain, so
 * that even the security and readiness filters log with them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LoggingContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val keys = contextOf(request.requestURI) + (REQUEST_ID to UUID.randomUUID().toString().substring(0, 8))
        keys.forEach { (key, value) -> MDC.put(key, value) }
        try {
            filterChain.doFilter(request, response)
        } finally {
            keys.keys.forEach(MDC::remove)
        }
    }

    companion object {
        const val REQUEST_ID = "requestId"
        const val CHANNEL_SESSION_ID = "channelSessionId"
        const val TOOL_SESSION_ID = "toolSessionId"

        private val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val CHANNEL = Regex("/channels/($UUID_PATTERN)")
        private val TOOL = Regex("/tools/($UUID_PATTERN)")

        /** The session ids [uri] names - a channel, a tool session, or both, or none. */
        fun contextOf(uri: String): Map<String, String> = buildMap {
            CHANNEL.find(uri)?.let { put(CHANNEL_SESSION_ID, it.groupValues[1]) }
            TOOL.find(uri)?.let { put(TOOL_SESSION_ID, it.groupValues[1]) }
        }
    }
}
