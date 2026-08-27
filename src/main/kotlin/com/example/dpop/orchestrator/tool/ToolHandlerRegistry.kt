package com.example.dpop.orchestrator.tool

import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component

/**
 * Aggregates the self-description every handler implements directly (docs/03-tool-architektur.md
 * #1) into the tool catalog - Spring collects `List<ToolDescriptor>` on its own, nothing here is
 * a manually maintained list.
 *
 * This used to wrap handlers behind a `ToolHandler` interface and resolve toolId -> handler for
 * a generic activate/patch/read dispatch; that dispatch is gone (docs/08-projektrahmen.md A11 -
 * each tool's own controller calls its concrete handler directly), so the wrapper interface was
 * removed and this is purely a descriptor catalog now.
 */
@Component
class ToolHandlerRegistry(descriptors: List<ToolDescriptor>) {
    private val descriptorsByToolId: Map<String, ToolDescriptor> = descriptors.associateBy { it.toolId }

    init {
        // (method, role) is meant to uniquely identify "the concrete procedure of this kind for
        // this credential" (docs/03-tool-architektur.md, MethodRole) - callers (e.g.
        // DefaultAuthPolicy.candidateTools) resolve a single descriptor by exactly this key and
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
    }

    fun descriptorOf(toolId: String): ToolDescriptor =
        descriptorsByToolId[toolId] ?: throw NoSuchElementException("Unknown toolId: $toolId")

    fun descriptors(): List<ToolDescriptor> = descriptorsByToolId.values.toList()
}
