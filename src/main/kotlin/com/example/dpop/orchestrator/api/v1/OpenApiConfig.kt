package com.example.dpop.orchestrator.api.v1

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Exposed at /v3/api-docs and /swagger-ui/index.html (docs/05-api.md). */
@Configuration
class OpenApiConfig {

    @Bean
    fun dpopDemoOpenApi(): OpenAPI = OpenAPI()
        /**
         * One description per tag, declared here rather than on the controllers. A method's tools
         * live in several controllers (enroll / auth / lookup are separate resources), and each
         * used to carry its own `description` on the SAME tag name - which produced a spec with
         * `Tool: SMS` listed three times with three different descriptions. Swagger UI then showed
         * whichever won, and the OpenAPI Generator rejected the spec outright.
         *
         * A tag names a method, and a method is one thing: its description belongs where it can
         * only be written once. The controllers keep `@Tag(name = ...)` with no description, which
         * makes their entries identical and lets springdoc collapse them into this one.
         */
        .tags(
            listOf(
                tag("Tool: SMS", "SMS-TAN: Einrichtung als Faktor, Anmeldung und Login ohne DPoP"),
                tag("Tool: E-Mail", "E-Mail: Bestaetigung der Adresse, Anmeldung und Login ohne DPoP"),
                tag("Tool: Passwort", "Passwort: Einrichtung als Wissensfaktor, Anmeldung und Login ohne DPoP"),
                tag("Tool: Gerät", "Geraetebindung: Einrichtung und Anmeldung ueber den Geraeteschluessel"),
                tag("Tool: QR-Login", "QR-Pairing: Freigabe eines Web-Kanals aus einer bereits angemeldeten App"),
                tag("Tool: KOBIL", "Gerätebindung über den externen Dienstleister KOBIL")
            )
        )
        .info(
            Info()
                .title("DPoP-Demo Orchestrator API")
                .version("v1")
                .description(
                    "App-facing (orchestrator-first) API for the DPoP-bound registration and login demo. " +
                        "See docs/05-api.md for the full contract; the Keycloak-facing kc facade is out of " +
                        "scope for this build (docs/08-projektrahmen.md)."
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

    private fun tag(name: String, description: String) = Tag().name(name).description(description)
}
