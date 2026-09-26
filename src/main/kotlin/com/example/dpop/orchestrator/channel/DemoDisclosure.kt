package com.example.dpop.orchestrator.channel

import com.example.dpop.tool_api.DemoInfo
import com.example.dpop.tool_api.DemoSession
import com.example.dpop.tool_api.JourneyDebugStep
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * The only thing in the system that may put demo-only values into a response.
 *
 * What travels in the `demo` block is not decoration: a plaintext TAN, a plaintext confirmation
 * code, the fixed demo password, every register persona's KVNR, name, address and FSC code. A
 * deployment that must not disclose any of it needs something to switch off - a doc comment
 * saying "never part of the production contract" is a convention, not a safeguard.
 *
 * So there is exactly one implementation able to produce a [DemoInfo], and whether it exists at
 * all is decided at startup by `demo.disclosure`. With it off, the bean in this file's place
 * returns `null` for every request and the plaintext values physically cannot reach a client -
 * the tools still attach them to their own outcome, but nothing carries them any further.
 *
 * Default ON: this project IS the demo, and the values are the point of it (docs/05-api.md #2).
 * The default is stated here rather than assumed, so turning it off is one setting rather than a
 * code change.
 *
 * `OrchestratorArchitectureTest` ("the demo block of a response") keeps this the only construction
 * site - otherwise a third assembly point could quietly reintroduce the unconditional path.
 */
interface DemoDisclosure {

    /**
     * @param values whatever the tool that just ran attached as
     *   [com.example.dpop.tool_spi.ToolOutcome.InProgress.demo], or null when it attached nothing.
     * @return the block to put in the response, or `null` when there is nothing to say - or
     *   nothing that may be said.
     */
    fun assemble(
        accountId: Long?,
        personId: String?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>? = null,
        includeWhenEmpty: Boolean = false,
        session: DemoSession? = null
    ): DemoInfo?
}

/**
 * The disclosing implementation - active unless `demo.disclosure=false`.
 *
 * `persons` (every person in the register, see [DemoPersonas]) is attached here, once, for every
 * caller rather than by each tool's own `demo` values, so a frontend persona picker works
 * everywhere without touching auth_sms/auth_email/auth_password/id_fsc/id_eid individually.
 */
@Component
@ConditionalOnProperty(name = ["demo.disclosure"], havingValue = "true", matchIfMissing = true)
class DisclosingDemoDisclosure(private val personas: DemoPersonas) : DemoDisclosure {

    override fun assemble(
        accountId: Long?,
        personId: String?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>?,
        includeWhenEmpty: Boolean,
        session: DemoSession?
    ): DemoInfo? {
        if (!includeWhenEmpty && values.isNullOrEmpty() && journeys.isEmpty() && session == null) return null
        return DemoInfo(
            accountId = accountId,
            personId = personId,
            journeys = journeys,
            session = session,
            values = (values ?: emptyMap()) + ("persons" to personas.all())
        )
    }
}

/**
 * `demo.disclosure=false`: nothing is disclosed, for any caller, ever. Not a filter over an
 * assembled block but the absence of one - there is no code path left that could build it.
 */
@Component
@ConditionalOnProperty(name = ["demo.disclosure"], havingValue = "false")
class WithheldDemoDisclosure : DemoDisclosure {

    override fun assemble(
        accountId: Long?,
        personId: String?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>?,
        includeWhenEmpty: Boolean,
        session: DemoSession?
    ): DemoInfo? = null
}
