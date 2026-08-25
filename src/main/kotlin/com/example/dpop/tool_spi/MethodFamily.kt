package com.example.dpop.tool_spi

/**
 * Identifies the credential family shared by several ToolDescriptors playing different
 * [MethodRole]s for the same underlying credential (docs/03-tool-architektur.md) - e.g. SMS
 * enrollment/device-auth/lookup-auth all reference the SAME instance. Object identity instead of
 * independently-typed string literals: a typo in one sibling's `"sms"` can no longer silently
 * create an unconnected third family.
 *
 * Deliberately a bare, business-unaware value type: tool_spi has no knowledge of which families
 * exist. Each module declares and owns its own instance (same "self-description, no centrally
 * maintained list" principle as the tool catalog itself, docs/03-tool-architektur.md #1) - adding
 * a new method never means editing a shared file here.
 */
data class MethodFamily(val method: String)
