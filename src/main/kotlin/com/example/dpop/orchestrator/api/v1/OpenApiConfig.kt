package com.example.dpop.orchestrator.api.v1

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Exposed at /v3/api-docs and /swagger-ui/index.html (docs/05-api.md). */
@Configuration
class OpenApiConfig {

    @Bean
    fun dpopDemoOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("DPoP-Demo Orchestrator API")
                .version("v1")
                .description(
                    "App-facing (orchestrator-first) API for the DPoP-bound registration and login demo. " +
                        "See docs/05-api.md for the full contract; the Keycloak-facing kc facade is out of " +
                        "scope for this build (docs/11-umsetzungsplan.md)."
                )
        )
        .components(
            Components().addSecuritySchemes(
                "dpop",
                SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .`in`(SecurityScheme.In.HEADER)
                    .name("DPoP")
                    .description("DPoP proof JWT (docs/09-dpop.md); required on every App-facade call.")
            )
        )
}
