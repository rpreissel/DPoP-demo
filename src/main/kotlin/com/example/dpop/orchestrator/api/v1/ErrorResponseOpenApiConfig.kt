package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.domain.ErrorCode
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Puts the error contract into the spec: [ErrorResponse] as a schema, and a `default` response on
 * every operation that points at it.
 *
 * `default` rather than a list of status codes per operation. `default` says exactly what is true -
 * every response not listed otherwise has this shape - without claiming which codes a given
 * endpoint can produce; nothing in the code knows that per endpoint, and a list maintained by hand
 * would be a guess that drifts.
 *
 * Which status belongs to which code is generated from [ErrorCode] into the schema's description,
 * so the table in the contract cannot disagree with the enum.
 */
@Configuration
class ErrorResponseOpenApiConfig {

    @Bean
    fun errorResponseContract(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val paths = openApi.paths ?: return@OpenApiCustomizer
        if (paths.isEmpty()) return@OpenApiCustomizer

        val components = openApi.components ?: io.swagger.v3.oas.models.Components().also { openApi.components = it }
        ModelConverters.getInstance().readAll(ErrorResponse::class.java).forEach { (name, schema) ->
            components.addSchemas(name, schema)
        }
        components.schemas[SCHEMA]?.description = DESCRIPTION

        val response = ApiResponse()
            .description("Error. `error` names the case; its HTTP status is fixed per code (see ErrorResponse).")
            .content(Content().addMediaType("application/json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$SCHEMA"))))
        paths.values.forEach { item ->
            item.readOperations().forEach { operation -> operation.responses?.addApiResponse("default", response) }
        }
    }

    private companion object {
        private const val SCHEMA = "ErrorResponse"
        private val DESCRIPTION = "Every error response has this shape. The HTTP status is fixed per `error` code:\n\n" +
            ErrorCode.entries.joinToString("\n") { "- `${it.name}`: ${it.httpStatus}" } +
            "\n\nA client must expect a code it does not know and handle it by its HTTP status."
    }
}
