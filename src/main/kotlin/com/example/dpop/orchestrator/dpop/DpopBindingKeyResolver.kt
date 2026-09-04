package com.example.dpop.orchestrator.dpop

import com.example.dpop.orchestrator.api.v1.DeviceChannelAccessGuard
import com.example.dpop.orchestrator.kc.PeerAuthValidator
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.buildRequestUrl
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves any `@BindingKey bindingKeyRef: String` controller parameter before the method body
 * runs (docs/04-orchestrierung.md #5) - replaces the old `DpopBaseController` pattern of every
 * controller calling `validateAndExtractBindingKeyRef` on its own `dpopProof`/`httpRequest`
 * parameters. A tool controller no longer needs [DpopValidator]/[JwkThumbprintService] at all, so
 * it no longer needs to depend on the orchestrator for them.
 *
 * Facade-aware (docs/ideen/web-keycloak-kanal.md #6): the generic tool endpoints
 * (`/channels/{id}/tools/{toolId}`, `/tools/{toolSessionId}/{toolId}`, ...) are facade-neutral by
 * design - the kc-facade's `OrchestratorAuthenticator` calls them exactly like the App client
 * does, just with a signed Keycloak assertion instead of a DPoP proof. Rather than duplicating
 * every tool controller under a `/kc/` prefix, this resolver picks the proof the caller actually
 * sent and folds it into the SAME `String` shape [ChannelAccessGuard]/[DeviceChannelAccessGuard]
 * already compare against - a `"kc:"`-prefixed anchor for Keycloak, the bare DPoP thumbprint
 * otherwise. `channelSessionId` isn't known yet at this point (a tool-session-keyed call resolves
 * it only later, via the ToolSession -> AuthJourney lookup) - only the anchor identity itself, not
 * which channel it belongs to, so the actual per-channel comparison still happens downstream.
 */
@Component
class DpopBindingKeyResolver(
    private val dpopValidator: DpopValidator,
    private val jwkThumbprintService: JwkThumbprintService,
    private val peerAuthValidator: PeerAuthValidator
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(BindingKey::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): String {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "@BindingKey resolution requires a servlet request"
        }
        val dpopProof = request.getHeader("DPoP")
        if (dpopProof != null) {
            val proof = dpopValidator.validate(dpopProof, request.method, buildRequestUrl(request))
            return jwkThumbprintService.computeThumbprint(proof.publicKey)
        }

        val authorization = request.getHeader("Authorization")
            ?: throw DpopValidationException("Missing DPoP proof")
        val token = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substring(7).trim()
        } else {
            authorization.trim()
        }
        val assertion = peerAuthValidator.validate(token, request.method, buildRequestUrl(request))
        val anchor = assertion.kcSessionId ?: assertion.kcAuthSessionId
            ?: throw DpopValidationException("Peer-auth assertion carries no kc-anchor")
        return "${DeviceChannelAccessGuard.KC_ANCHOR_PREFIX}$anchor"
    }
}
