package com.example.dpop.tool_spi

import com.example.dpop.texts.Text

/**
 * Thrown when a tool cannot resolve a reference it was handed (e.g. an unknown or missing
 * [EnrollmentRef]). Mapped to `HTTP 422 Unprocessable Entity`, carrying [text]; the ids behind it
 * go only to the log, as `detail`.
 */
class UnresolvableReferenceException(val text: Text, detail: String? = null) :
    RuntimeException(detail?.let { "${text.template} ($it)" } ?: text.template)
