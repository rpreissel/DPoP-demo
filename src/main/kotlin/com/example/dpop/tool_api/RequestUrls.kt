package com.example.dpop.tool_api

import jakarta.servlet.http.HttpServletRequest

/**
 * Reconstructs the full URL the client actually called, in the `htu` shape DPoP and device
 * proofs are checked against (scheme, host, port if non-default, and path - no query string).
 *
 * **Behind a reverse proxy this needs `server.forward-headers-strategy`.** The values below come
 * from the connection the servlet container sees, which is the proxy's - not the client's. A
 * proxy that terminates TLS makes every proof fail with "htu claim does not match request URL",
 * because the client signed `https://host/...` and this builds `http://host:8080/...`. Turning
 * the strategy on lets `ForwardedHeaderFilter` restore the original scheme/host/port first (see
 * `application-fly.yml`), which is safe only where a trusted proxy overwrites `X-Forwarded-*`.
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
