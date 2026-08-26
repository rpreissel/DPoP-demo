package com.example.dpop.tool_spi

/**
 * Thrown when a tool cannot resolve a reference it was handed (e.g. an unknown or missing
 * [EnrollmentRef]). Mapped to `HTTP 422 Unprocessable Entity`.
 */
class UnresolvableReferenceException(message: String) : RuntimeException(message)
