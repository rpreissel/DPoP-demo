package com.example.dpop.orchestrator.dpop

import com.example.dpop.tool_api.BindingKey
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
 */
@Component
class DpopBindingKeyResolver(
    private val dpopValidator: DpopValidator,
    private val jwkThumbprintService: JwkThumbprintService
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
            ?: throw DpopValidationException("Missing DPoP proof")
        val proof = dpopValidator.validate(dpopProof, request.method, buildRequestUrl(request))
        return jwkThumbprintService.computeThumbprint(proof.publicKey)
    }
}
