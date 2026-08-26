package com.example.dpop.tool_api

import jakarta.servlet.http.HttpServletRequest

/**
 * The `htu` shape DPoP and device proofs are checked against. A pure function of the request, not
 * DPoP-specific and not the orchestrator's to own: lives here so a method module's controller
 * (auth-device/enroll-device build their own device-proof `htu` from it) never needs to depend on
 * `orchestrator` just to construct a URL string (docs/04-orchestrierung.md #5, DPoP-demo-2tm).
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
