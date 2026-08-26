package com.example.dpop.tool_spi

/**
 * Reserved key in [ToolOutcome.InProgress.data] for a nested bag of demo-only values (e.g. a
 * just-issued TAN). Never part of the production `stepData` contract - the caller lifts this key
 * out into a separate `demo` block before building the client response.
 */
const val DEMO_DATA_KEY = "demo"

/**
 * Builds the `data` entry a tool uses to attach demo-only values, e.g.:
 * ```
 * data = mapOf("missingFields" to listOf("tan"), demoData("tan" to issued.plainTan))
 * ```
 */
fun demoData(vararg values: Pair<String, Any?>): Pair<String, Map<String, Any?>> = DEMO_DATA_KEY to mapOf(*values)

/**
 * The email address this demo environment treats as already confirmed. Shared as one literal
 * across every method module so a lookup-based login tool can resolve the same account another
 * tool's demo flow just enrolled.
 */
const val DEMO_EMAIL = "max.mustermann@example.com"
