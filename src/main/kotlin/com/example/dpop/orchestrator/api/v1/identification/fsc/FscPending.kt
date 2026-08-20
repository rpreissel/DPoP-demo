package com.example.dpop.orchestrator.api.v1.identification.fsc

@JvmRecord
data class FscPending(val kvnr: String?, val fsc: String?) {

    fun merge(patch: Map<String, Any>?): FscPending {
        if (patch == null) return this
        val newKvnr = if (patch.containsKey("kvnr")) patch["kvnr"] as String? else kvnr
        val newFsc = if (patch.containsKey("fsc")) patch["fsc"] as String? else fsc
        return FscPending(newKvnr, newFsc)
    }

    fun missingFields(): List<String> {
        val missing = mutableListOf<String>()
        if (kvnr.isNullOrBlank()) missing.add("kvnr")
        if (fsc.isNullOrBlank()) missing.add("fsc")
        return missing
    }

    val isComplete: Boolean get() = missingFields().isEmpty()

    companion object {
        @JvmStatic
        fun empty(): FscPending = FscPending(null, null)
    }
}
