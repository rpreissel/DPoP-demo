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
 * the strategy on lets `ForwardedHeaderFilter` restore the original scheme/host/port first, which
 * is safe only where a trusted proxy overwrites `X-Forwarded-*`.
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

/**
 * Whether a proof's `htu` names the request it came with (RFC 9449 section 4.3): query and fragment
 * ignored, scheme and host compared without regard to case, a default port counts as absent - and
 * the path compared exactly. It used to be one case-insensitive comparison of the whole URL, three
 * times over (DPoP, device proofs, peer-auth); paths are case-sensitive, so `/Tools/x` is not
 * `/tools/x` (review 2026-09-26, F-10). An `htu` that is no absolute http(s) URL matches nothing.
 */
fun htuMatches(htu: String, requestUrl: String): Boolean {
    val claimed = targetOf(htu) ?: return false
    val actual = targetOf(requestUrl) ?: return false
    return claimed == actual
}

private data class Target(val scheme: String, val host: String, val port: Int, val path: String)

private fun targetOf(url: String): Target? {
    val uri = runCatching { java.net.URI(url.substringBefore('#').substringBefore('?')) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
    return Target(scheme, host, port, uri.rawPath.orEmpty().ifEmpty { "/" })
}
