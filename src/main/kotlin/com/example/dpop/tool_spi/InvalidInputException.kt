package com.example.dpop.tool_spi

import com.example.dpop.texts.Text

/**
 * A value the user entered was rejected (a malformed phone number, a too short password) -
 * `HTTP 400` carrying [text]. An [IllegalArgumentException] like every rejected input; this one
 * says so in words the user reads, where a plain `require` only names the technical fault.
 */
class InvalidInputException(val text: Text) : IllegalArgumentException(text.template)
