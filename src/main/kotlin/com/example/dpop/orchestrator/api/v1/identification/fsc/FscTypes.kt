package com.example.dpop.orchestrator.api.v1.identification.fsc

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FscPending(val kvnr: String?, val fsc: String?) {

    fun merge(patch: Map<String, Any>?): FscPending {
        if (patch == null) return this
        return copy(
            kvnr = patch["kvnr"] as? String ?: kvnr,
            fsc = patch["fsc"] as? String ?: fsc
        )
    }

    fun missingFields(): List<String> = buildList {
        if (kvnr.isNullOrBlank()) add("kvnr")
        if (fsc.isNullOrBlank()) add("fsc")
    }

    val isComplete: Boolean get() = missingFields().isEmpty()

    companion object {
        fun empty(): FscPending = FscPending(null, null)
    }
}

sealed interface FscStep {
    data class NeedInput(val missingFields: List<String>) : FscStep
    data class Verify(val kvnr: String, val fsc: String) : FscStep
}
