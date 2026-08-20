package com.example.dpop.orchestrator.dpop

class DpopValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
