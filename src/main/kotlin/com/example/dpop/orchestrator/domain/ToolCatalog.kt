package com.example.dpop.orchestrator.domain

import com.example.dpop.tool_spi.ToolDescriptor
import com.example.dpop.tool_spi.ToolId

/**
 * What the domain may ask about the tools: their self-descriptions. The strategies and the policy
 * read the catalog through this, never through the Spring component that assembles it
 * (`ToolHandlerRegistry`) - so the domain stays free of the framework
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */
interface ToolCatalog {
    fun descriptors(): List<ToolDescriptor>

    /** The descriptor of [toolId]; unknown ids are a broken assumption, not a case to branch on. */
    fun descriptorOf(toolId: ToolId): ToolDescriptor
}
