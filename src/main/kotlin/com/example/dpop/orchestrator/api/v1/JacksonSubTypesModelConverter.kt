package com.example.dpop.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fills in the `discriminator.mapping` of a Jackson-polymorphic DTO from its own
 * `@JsonSubTypes` declaration.
 *
 * swagger-core emits `discriminator.propertyName` for a `@JsonTypeInfo` type, but no mapping from
 * the discriminator VALUE to the subtype schema. The result reads as polymorphic while omitting
 * the one fact a reader needs: `KobilUnlockCredential` said `propertyName: kind` without ever
 * saying that `kind: "biometric"` means `BiometricUnlock`.
 *
 * That gap is not cosmetic for a generator. The TypeScript client types `kind` as a bare `String`
 * instead of `'biometric' | 'password'`, so it cannot narrow the union. Worse, a generator that
 * reconstructs the mapping itself falls back to the SCHEMA NAMES - it would send
 * `kind: "BiometricUnlock"`, which Jackson rejects. The wire format would be wrong, silently.
 *
 * Derived rather than declared: `@Schema(discriminatorMapping = ...)` would work, but it repeats
 * the `@JsonSubTypes` list by hand - a second statement about the same thing, to be kept in step.
 * That is exactly what [KotlinRequiredModelConverter] next door avoids for requiredness, and the
 * reasoning is the same: the spec should follow the declaration, not restate it.
 */
@Configuration
class JacksonSubTypesModelConverterConfig {

    @Bean
    fun jacksonSubTypesModelConverter(): ModelConverter = JacksonSubTypesModelConverter().also {
        ModelConverters.getInstance().addConverter(it)
        ModelConverters.getInstance(true).addConverter(it)
    }
}

internal class JacksonSubTypesModelConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>
    ): Schema<*>? {
        val resolved = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val raw = runCatching { Json.mapper().constructType(type.type)?.rawClass }.getOrNull()
            ?: return resolved

        val typeInfo = raw.getAnnotation(JsonTypeInfo::class.java) ?: return resolved
        if (typeInfo.use != JsonTypeInfo.Id.NAME) return resolved
        val subTypes = raw.getAnnotation(JsonSubTypes::class.java)?.value ?: return resolved

        val target = schemaCarrying(resolved, context) ?: return resolved
        val discriminator = target.discriminator ?: return resolved

        subTypes.forEach { subType ->
            val name = subType.name.takeIf { it.isNotEmpty() } ?: return@forEach
            if (discriminator.mapping?.containsKey(name) == true) return@forEach
            // Resolve the subtype first: it may not have been reached through any controller
            // signature yet, and a mapping pointing at a schema that does not exist is worse than
            // no mapping at all.
            val subSchema = context.resolve(AnnotatedType(subType.value.java))
            val schemaName = subSchema?.`$ref`?.substringAfterLast('/')
                ?: subType.value.java.simpleName
            discriminator.mapping(name, "#/components/schemas/$schemaName")
        }
        return resolved
    }

    /**
     * The schema that actually carries the discriminator. A top-level resolve returns a `$ref`,
     * and the named model behind it is where swagger-core put the `discriminator` - the same
     * two-step [KotlinRequiredModelConverter] needs for the required list.
     */
    private fun schemaCarrying(resolved: Schema<*>?, context: ModelConverterContext): Schema<*>? =
        resolved?.takeIf { it.discriminator != null }
            ?: resolved?.`$ref`?.let { context.getDefinedModels()[it.substringAfterLast('/')] }
}
