package com.example.dpop.orchestrator.api.v1.identification.fsc

sealed interface FscStep {
    @JvmRecord
    data class NeedInput(val missingFields: List<String>) : FscStep

    @JvmRecord
    data class Verify(val kvnr: String?, val fsc: String?) : FscStep
}
