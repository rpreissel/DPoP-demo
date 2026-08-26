package com.example.dpop.tool_api

import jakarta.servlet.http.HttpServletRequest

/**
 * Reconstructs the full URL the client actually called, in the `htu` shape DPoP and device
 * proofs are checked against (scheme, host, port if non-default, and path - no query string).
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
