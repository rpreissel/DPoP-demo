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

    fun descriptorOf(toolId: String): ToolDescriptor =
        descriptorsByToolId[toolId] ?: throw NoSuchElementException("Unknown toolId: $toolId")

    fun descriptors(): List<ToolDescriptor> = descriptorsByToolId.values.toList()
}
