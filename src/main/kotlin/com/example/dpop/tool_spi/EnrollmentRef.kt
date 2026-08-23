package com.example.dpop.tool_spi

/** Opaque reference into a module's enrollment record (docs/03-tool-architektur.md #1). */
data class EnrollmentRef(val type: String, val id: String)
