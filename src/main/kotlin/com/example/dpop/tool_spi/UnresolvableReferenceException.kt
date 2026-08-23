package com.example.dpop.tool_spi

/** A tool cannot resolve a reference it was handed (e.g. unknown/missing enrollment); maps to HTTP 422. */
class UnresolvableReferenceException(message: String) : RuntimeException(message)
