package com.example.dpop.orchestrator.dpop

import jakarta.servlet.http.HttpServletRequest

/**
 * The `htu` shape DPoP proofs are checked against - not DPoP-specific itself, so also reused by
 * device-proof validation (auth-device/enroll-device), which authenticates a second, independent
 * proof over the same exact URL shape.
 */
fun buildRequestUrl(request: HttpServletRequest): String = buildString {
    append(request.scheme).append("://").append(request.serverName)
    val port = request.serverPort
    val scheme = request.scheme
    if ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) {
        append(":").append(port)
    }
    append(request.requestURI)
}
