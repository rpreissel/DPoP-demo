package com.example.dpop.orchestrator.api.v1

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Marks a DTO property `required` in the OpenAPI schema when its Kotlin type is non-nullable.
 *
 * swagger-core decides requiredness from Jackson/Bean-Validation annotations, which know nothing
 * about Kotlin's type-level nullability - so every property of every Kotlin DTO came out optional.
 * The effect was not cosmetic: the generated client types (`frontend/src/generated`, from
 * `api/openapi.yaml`) made `ChannelResponse.channel` optional, which is the one field every single
 * response carries, and the client would have had to null-check what the backend guarantees.
 *
 * The rule is exactly the declaration the DTO already makes: a non-nullable Kotlin property cannot
 * be absent from a serialized response, so the schema says so. Nothing has to be repeated in an
 * annotation, and a property that later becomes nullable loses its `required` entry on its own -
 * the spec follows the type, rather than a second, hand-maintained statement about it.
 *
 * Deliberately NOT applied to properties with a default value: a Kotlin default means "the caller
 * may omit this", which for a request DTO is precisely what optional means. Only a property that
 * has no default AND cannot be null is something both sides can rely on being there.
 */
@Configuration
class KotlinRequiredModelConverterConfig {

    @Bean
    fun kotlinRequiredModelConverter(): ModelConverter = KotlinRequiredModelConverter().also {
        ModelConverters.getInstance().addConverter(it)
        ModelConverters.getInstance(true).addConverter(it)
    }
}

internal class KotlinRequiredModelConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>
    ): Schema<*>? {
        val resolved = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val kClass = kotlinClassOf(type) ?: return resolved

        // A $ref-only schema carries no properties of its own - the named schema it points at was
        // (or will be) resolved in its own pass, and that is where the required list belongs.
        val target = resolved?.takeIf { it.properties != null }
            ?: resolved?.`$ref`?.let { context.getDefinedModels()[it.substringAfterLast('/')] }
            ?: return resolved

        requiredPropertyNames(kClass)
            .filter { target.properties.containsKey(it) }
            // Idempotent, because swagger-core's ModelConverters is a JVM-wide singleton: a test
            // run that builds several Spring contexts registers this converter once per context,
            // and addRequiredItem appends unconditionally. Without the guard the same property
            // lands in `required` several times and the spec differs between a single-test run and
            // a full suite - which is exactly how OpenApiSnapshotTest first caught it.
            .filter { target.required?.contains(it) != true }
            .forEach { target.addRequiredItem(it) }

        // And the other direction: springdoc 3 reads Kotlin non-nullability itself and marks such
        // a property required even when it has a default - `availableTools: List<String> =
        // emptyList()` would become mandatory in every request. A default means "may be omitted",
        // so it comes back out, whoever put it in.
        val optional = optionalPropertyNames(kClass)
        target.required?.takeIf { required -> required.any { it in optional } }?.let { required ->
            target.required = required.filterNot { it in optional }.ifEmpty { null }
        }

        return resolved
    }

    private fun optionalPropertyNames(kClass: KClass<*>): Set<String> {
        val primary = runCatching { kClass.constructors.firstOrNull() }.getOrNull() ?: return emptySet()
        return primary.parameters.filter { it.isOptional }.mapNotNull { it.name }.toSet()
    }

    /**
     * Only Kotlin classes, and only ones declaring a primary constructor - that is what a DTO is
     * here. Anything else (Java types, enums, collections) is left exactly as swagger-core
     * resolved it.
     */
    private fun kotlinClassOf(type: AnnotatedType): KClass<*>? {
        val javaType = Json.mapper().constructType(type.type) ?: return null
        // A type documented as another (`@Schema(implementation = ...)`, e.g. Text -> TextRef) is
        // on the wire what that other one is - its properties decide what is required, not ours.
        val raw = javaType.rawClass.let { declared ->
            declared.getAnnotation(io.swagger.v3.oas.annotations.media.Schema::class.java)?.implementation?.java
                ?.takeIf { it != Void::class.java } ?: declared
        }
        if (raw.getAnnotation(Metadata::class.java) == null) return null
        if (raw.isEnum) return null
        return runCatching { raw.kotlin.takeIf { it.constructors.isNotEmpty() } }.getOrNull()
    }

    private fun requiredPropertyNames(kClass: KClass<*>): List<String> {
        val primary = runCatching { kClass.constructors.firstOrNull() }.getOrNull() ?: return emptyList()
        return runCatching {
            val optionalByName = primary.parameters.associate { it.name to it.isOptional }
            kClass.memberProperties
                .filter { property ->
                    // A property the constructor does not take is a computed one - always present
                    // in the response, so its own nullability is the whole answer.
                    val hasDefault = optionalByName[property.name] == true
                    !hasDefault && !property.returnType.isMarkedNullable && property.returnType.jvmErasure != Unit::class
                }
                .map { it.name }
        }.getOrDefault(emptyList())
    }
}
