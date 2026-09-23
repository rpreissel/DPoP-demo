package com.example.dpop.orchestrator.api.v1

import com.example.dpop.tool_api.BindingKey
import io.swagger.v3.oas.models.security.SecurityRequirement
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod

/**
 * Describes how a caller proves who it is, instead of inventing a query parameter that nobody
 * sends.
 *
 * `@BindingKey` marks a controller parameter that [DpopBindingKeyResolver] fills in from the
 * request: either from the `DPoP` proof header (App channel) or from the peer-auth assertion in
 * the `Authorization` header (Web channel). It is never read from the URL.
 *
 * springdoc does not know about `HandlerMethodArgumentResolver`s, so it fell back to its default
 * assumption and documented the parameter as `in: query, required: true` - on 74 paths, 82 times.
 * That is not a cosmetic slip: the published contract told every client to put its own binding key
 * in the URL, which is the opposite of what DPoP means. The key is derived from a proof of
 * possession; a caller cannot choose it. The real frontend never sent it, but a client generated
 * from that contract would have.
 *
 * Removing the parameter alone would leave a second untruth behind - the endpoints would then
 * claim to need no authentication at all. So this configuration also states what they really require.
 */
@Configuration
class BindingKeyOpenApiConfig {

    /**
     * Keeps the parameter from being built in the first place. springdoc checks this list in
     * `AbstractRequestService.isParamToIgnore()` before it inspects anything else, which is
     * cheaper and less brittle than letting it build the parameter and removing it again in a
     * `ParameterCustomizer`.
     *
     * The list is a JVM-wide singleton, like swagger-core's `ModelConverters` next door in
     * [KotlinRequiredModelConverterConfig]. Registering twice (a test run with several Spring
     * contexts) is harmless here: the lookup is a membership test, not an accumulating list.
     */
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(BindingKey::class.java)
    }

    /**
     * Declares the two proofs an endpoint accepts. Two separate [SecurityRequirement] entries mean
     * OR in OpenAPI, which is exactly what [DpopBindingKeyResolver] does: a `DPoP` header, or else
     * a bearer peer-auth assertion.
     *
     * Attached per operation rather than globally, because it must say something true about each
     * one: endpoints without a `@BindingKey` parameter (the tool catalog, the mock-Keycloak
     * fixtures) genuinely need neither.
     */
    @Bean
    fun bindingKeySecurityCustomizer(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        if (requiresCallerProof(handlerMethod)) {
            operation.security = listOf(
                SecurityRequirement().addList(DPOP_SCHEME),
                SecurityRequirement().addList(PEER_AUTH_SCHEME)
            )
        }
        operation
    }

    private fun requiresCallerProof(handlerMethod: HandlerMethod): Boolean =
        handlerMethod.methodParameters.any { it.hasParameterAnnotation(BindingKey::class.java) }

    companion object {
        /** Declared in [OpenApiConfig]; the App channel's DPoP proof header. */
        const val DPOP_SCHEME = "dpop"

        /**
         * The Web channel's signed peer-auth assertion (docs/05-api.md Abschnitt 3, ADR-7).
         *
         * This spelling, not a new one: `KcChannelController` and `MgmtPasswordController` have
         * carried `@SecurityRequirement(name = "kc-peer-auth")` all along - but nothing ever
         * DECLARED a scheme under that name, so those references dangled and the document was
         * invalid. Found by openapi-diff, which refused to read the spec at all
         * ("Impossible to find security scheme: kc-peer-auth").
         */
        const val PEER_AUTH_SCHEME = "kc-peer-auth"
    }
}
