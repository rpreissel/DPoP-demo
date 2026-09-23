package com.example.dpop.orchestrator.api.v1

import com.example.dpop.tool_spi.StepData
import com.example.dpop.tool_spi.StepDataTypes
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Configuration

/**
 * Puts every declared [StepData] shape into the API description.
 *
 * Nothing else would: the shapes travel through `ToolOutcome`, never through a controller
 * signature, so springdoc has no way to reach them. Without this, `stepData` would be described as
 * an empty object - worse than the untyped map it replaced.
 *
 * The list comes from the modules themselves ([StepDataTypes] beans), not from a constant here.
 * A new method module declares its shapes next to its tools and appears in the contract for that
 * reason alone - the same rule the tool catalog, the retention sweep and the Flyway locations
 * already follow.
 */
@Configuration
class StepDataSchemaCustomizer {

    /**
     * One customizer per group, because a group must list only the shapes its own endpoints can
     * actually answer with. `auth_sms` can return its own shapes, the orchestrator's screens or the
     * shared [com.example.dpop.tool_spi.MissingFields] - never KOBIL's. Injecting every shape into
     * every group would put KOBIL's SDK parameters into the SMS module's contract.
     */
    fun forPackage(scannedPackage: String, declarations: List<StepDataTypes>): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val shapes = declarations.flatMap { it.types() }
            .distinct()
            .filter { belongsTo(it.java, scannedPackage) }
        if (shapes.isEmpty()) return@OpenApiCustomizer

        val components = openApi.components ?: return@OpenApiCustomizer
        val base = components.schemas?.get(STEP_DATA_SCHEMA) ?: return@OpenApiCustomizer

        val discriminator = Discriminator().propertyName(DISCRIMINATOR)
        shapes.sortedBy { it.simpleName }.forEach { shape ->
            val java = shape.java
            // Resolve into the document first: the shape has no other way in, and a $ref to a
            // schema that does not exist is worse than no reference at all.
            ModelConverters.getInstance().readAll(java).forEach { (name, schema) ->
                components.schemas.putIfAbsent(name, schema)
            }
            val name = ModelConverters.getInstance().read(AnnotatedType(java)).keys.firstOrNull()
                ?: java.simpleName
            base.addOneOfItem(Schema<Any>().`$ref`("#/components/schemas/$name"))
            discriminator.mapping(typeNameOf(java), "#/components/schemas/$name")
        }
        base.discriminator = discriminator
    }

    /**
     * The value Jackson writes into `@t`. `@JsonTypeName` when the shape declares one, otherwise
     * Jackson's own default - the simple class name. Read from the same annotation Jackson reads,
     * so the contract cannot disagree with the wire.
     */
    /**
     * Its own module's, plus the two every response can carry: the shared shapes in `tool_spi` and
     * the orchestrator's own screens, which any tool endpoint may answer with when the journey
     * moves on (the fallback chain).
     */
    private fun belongsTo(java: Class<*>, scannedPackage: String): Boolean {
        val pkg = java.packageName
        return pkg.startsWith(scannedPackage) ||
            pkg.startsWith(SHARED_PACKAGE) ||
            pkg.startsWith(ORCHESTRATOR_PACKAGE)
    }

    private fun typeNameOf(java: Class<*>): String =
        java.getAnnotation(JsonTypeName::class.java)?.value?.takeIf { it.isNotEmpty() }
            ?: java.simpleName

    private companion object {
        private const val STEP_DATA_SCHEMA = "StepData"
        private const val DISCRIMINATOR = "@t"
        private const val SHARED_PACKAGE = "com.example.dpop.tool_spi"
        private const val ORCHESTRATOR_PACKAGE = "com.example.dpop.orchestrator"
    }
}
