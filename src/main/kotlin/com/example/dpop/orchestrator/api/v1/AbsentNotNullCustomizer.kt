package com.example.dpop.orchestrator.api.v1

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Takes `null` back out of the contract: an optional field is ABSENT on the wire, never `null`.
 *
 * springdoc 3 reads Kotlin's nullability and writes every `String?` as `type: [string, 'null']`
 * and every nullable reference as `oneOf: [$ref, {type: 'null'}]`. For this API that says
 * something untrue. Every response DTO is `@JsonInclude(NON_NULL)` (`tool_api/Envelope.kt`,
 * `Next.kt`, ...), so a null property never reaches the client - it is simply not there. And
 * whether a property may be missing is already stated, once, by the `required` list that
 * [KotlinRequiredModelConverter] derives from the same Kotlin declaration.
 *
 * Left in, the second statement cost real precision: the generated TypeScript types became
 * `string | null | undefined` for values that are only ever `string` or absent, and every nullable
 * reference - `stepData`, `authData`, `next` - turned into a two-way union that wraps the
 * discriminated `StepData` union in yet another one.
 *
 * Request DTOs follow the same rule: a Kotlin default of `null` means "may be omitted", and
 * omitting is what the contract now tells a client to do.
 */
@Configuration
class AbsentNotNullCustomizerConfig {

    @Bean
    fun absentNotNullCustomizer(): GlobalOpenApiCustomizer = GlobalOpenApiCustomizer { openApi ->
        AbsentNotNull.apply(openApi)
    }
}

internal object AbsentNotNull {

    private const val NULL = "null"

    fun apply(openApi: OpenAPI) {
        openApi.components?.schemas?.values?.forEach(::normalize)
        openApi.paths?.values?.forEach { path ->
            path.readOperations().forEach { operation ->
                operation.parameters?.forEach { normalize(it.schema) }
                operation.requestBody?.content?.values?.forEach { normalize(it.schema) }
                operation.responses?.values?.forEach { response ->
                    response.content?.values?.forEach { normalize(it.schema) }
                }
            }
        }
    }

    private fun normalize(schema: Schema<*>?) {
        if (schema == null) return
        schema.types?.takeIf { NULL in it && it.size > 1 }?.let { types ->
            schema.types = types - NULL
        }
        if (schema.type == NULL && schema.types?.contains(NULL) != true) schema.type = null
        unwrapNullableUnion(schema)

        schema.properties?.values?.forEach(::normalize)
        normalize(schema.items)
        (schema.additionalProperties as? Schema<*>)?.let(::normalize)
        schema.allOf?.forEach(::normalize)
        schema.oneOf?.forEach(::normalize)
        schema.anyOf?.forEach(::normalize)
    }

    /** `oneOf: [X, {type: 'null'}]` is just X; the schema's own description etc. stay where they are. */
    private fun unwrapNullableUnion(schema: Schema<*>) {
        val oneOf = schema.oneOf ?: return
        val nonNull = oneOf.filterNot(::isNullOnly)
        if (nonNull.size == oneOf.size || nonNull.size != 1) return
        val only = nonNull.single()
        schema.oneOf = null
        if (only.`$ref` != null) {
            schema.`$ref` = only.`$ref`
        } else {
            schema.oneOf = listOf(only)
        }
    }

    private fun isNullOnly(schema: Schema<*>): Boolean =
        schema.`$ref` == null && (schema.types == setOf(NULL) || (schema.types == null && schema.type == NULL))
}
