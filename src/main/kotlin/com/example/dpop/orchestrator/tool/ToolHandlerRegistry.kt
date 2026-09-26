package com.example.dpop.orchestrator.tool

import com.example.dpop.texts.Text
import com.example.dpop.orchestrator.kernel.OrchestratorException
import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolId
import org.springframework.stereotype.Component

/**
 * Aggregates the self-description every handler implements directly (docs/03-tool-architektur.md
 * #1) into the tool catalog - Spring collects `List<ToolDescriptor>` on its own, nothing here is
 * a manually maintained list.
 *
 * Purely a descriptor catalog: each tool's own controller calls its concrete handler directly
 * (docs/08-projektrahmen.md A11), so there is no toolId -> handler dispatch and no
 * `ToolHandler` wrapper interface.
 */
@Component
class ToolHandlerRegistry(descriptors: List<ToolDescriptor>) {
    private val descriptorsByToolId: Map<ToolId, ToolDescriptor> = descriptors.associateBy { it.toolId }

    init {
        // (method, role) is meant to uniquely identify "the concrete procedure of this kind for
        // this credential" (docs/03-tool-architektur.md, MethodRole) - callers (e.g.
        // DefaultAuthPolicy.authCandidates) resolve a single descriptor by exactly this key and
        // trust the result unambiguously. A duplicate would silently resolve to whichever
        // descriptor happens to iterate first, not a loud error - fail at startup instead, since
        // nothing else here would ever catch it.
        val duplicates = descriptorsByToolId.values
            .groupBy { it.method to it.role }
            .filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            val details = duplicates.entries.joinToString("; ") { (key, group) ->
                "${key.first}/${key.second}: ${group.map { it.toolId }}"
            }
            "Duplicate (method, role) in tool catalog: $details"
        }
        // A KVNR is only ever vouched for by the Personenverzeichnis itself. Identity matching has
        // no path for a KVNR a tool merely read (it used to have one, unreachable and unchecked -
        // review 2026-09, Phase F); a tool declaring one must fail here, not open that path silently.
        val unvouchedKvnr = descriptorsByToolId.values.filter { descriptor ->
            descriptor.claims.any { it.attributeType == AttributeType.KVNR && it.source != ClaimSource.PERSON_DIRECTORY }
        }
        check(unvouchedKvnr.isEmpty()) {
            "Only the Personenverzeichnis may vouch for a KVNR, but ${unvouchedKvnr.map { it.toolId }} declare one from elsewhere"
        }
        // Every identification must be findable again by what a person can still tell us years
        // later - name, first name, date of birth - even after the account is deleted (the change
        // log's search key, ADR-39). A procedure not delivering all three would leave its accounts
        // unfindable; ident-fsc once did, for the date of birth.
        val unfindable = descriptorsByToolId.values.filter { descriptor ->
            descriptor.role == MethodRole.IDENTIFICATION &&
                !descriptor.claims.map { it.attributeType }.containsAll(FINDABLE_BY)
        }
        check(unfindable.isEmpty()) {
            "Every identification procedure must declare $FINDABLE_BY, but ${unfindable.map { it.toolId }} do not"
        }
    }

    fun descriptorOf(toolId: ToolId): ToolDescriptor =
        descriptorsByToolId[toolId] ?: throw OrchestratorException.notFound(Text("Unknown tool"), "toolId=${toolId}")

    fun descriptors(): List<ToolDescriptor> = descriptorsByToolId.values.toList()

    private companion object {
        val FINDABLE_BY = setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM)
    }
}
