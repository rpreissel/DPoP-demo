package com.example.dpop.tool_spi

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass

/**
 * What one step needs the client to show.
 *
 * It used to be a bare `Map<String, Any?>`, which made it the one part of the response the
 * contract said nothing about: a client had to know from prose which keys a given step carries.
 * Now every shape is a declared type, and the object names itself through `kind`.
 *
 * **The discriminator has to be inside the object.** What a step shows depends on the step, and
 * the step is named by the sibling `next` - but OpenAPI can only discriminate on a property of the
 * object itself.
 *
 * **Why `kind` and not `@t`.** `@t` is the Jackson convention for the journey states this backend
 * persists, and that is where it belongs. On the wire it broke the generated TypeScript: the
 * generator turns the name into a legal identifier and wrote the union as `{ t: 'confirm' }`, so a
 * client dispatching on the generated type would always have read `undefined`. `kind` is what the
 * contract already used for the KOBIL unlock credential - one
 * discriminator name on the wire, not two.
 *
 * **Keyed by the step, not by the endpoint.** A tool's response regularly carries another step's
 * data: abandoning `auth-sms` answers with the orchestrator's selection screen, and a completed
 * tool answers with whatever comes next. `Step` pairs this with the `next` it belongs to
 * (`JourneyService`), and that pairing is the whole rule.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@Schema(
    description = "What the current step needs to render. `kind` names the shape; see the mapping " +
        "on this schema for the ones this deployment can produce."
)
interface StepData

/**
 * The shape almost every tool step has: which inputs are still missing.
 *
 * Lives in `tool_api` rather than in one module because it is genuinely shared - `ident-fsc`,
 * `enroll-sms`, `auth-password` and a dozen others all say exactly this and nothing more. A type
 * per module would be the same declaration written fourteen times.
 */
@JsonTypeName("missing-fields")
@Schema(description = "Which inputs this step is still waiting for.")
data class MissingFields(
    @field:Schema(example = "[\"tan\"]")
    val missingFields: List<String>
) : StepData

/**
 * How a module tells the API description which [StepData] shapes it can produce.
 *
 * Needed because nothing else can find them: the subtypes are returned through `ToolOutcome`, not
 * through any controller signature, so springdoc never sees them. A `@JsonSubTypes` list on
 * [StepData] would work but would have to name every module's types in `tool_api` - the central
 * list this project avoids everywhere else, and one a new module would be forgotten from.
 *
 * Instead each module contributes a bean of this type next to its tools. Spring collects them, and
 * `StepDataSchemaCustomizer` turns them into the `oneOf` and the discriminator mapping. A module
 * that declares a new shape and no bean simply does not appear in the contract - which
 * `StepDataCoverageTest` reports.
 */
fun interface StepDataTypes {
    fun types(): List<KClass<out StepData>>
}

/** The one shape `tool_spi` owns itself, because it is shared rather than any single module's. */
@Configuration
class SharedStepDataTypes {

    @Bean
    fun sharedStepDataShapes() = StepDataTypes { listOf(MissingFields::class) }
}
