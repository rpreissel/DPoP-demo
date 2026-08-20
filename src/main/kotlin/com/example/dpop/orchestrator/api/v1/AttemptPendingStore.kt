package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.session.OrchestratorAttempt
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class AttemptPendingStore(internal val objectMapper: ObjectMapper) {

    fun save(attempt: OrchestratorAttempt, pending: Any) {
        try {
            attempt.result = objectMapper.writeValueAsString(mapOf("pending" to pending))
        } catch (e: Exception) {
            throw IllegalStateException("Pending-Daten konnten nicht serialisiert werden", e)
        }
    }

    companion object {
        internal val MAP_TYPE = object : TypeReference<Map<String, Any>>() {}
    }
}

internal inline fun <reified T> AttemptPendingStore.load(attempt: OrchestratorAttempt): T? {
    val result = attempt.result ?: return null
    if (result.isBlank()) return null
    return try {
        val parsed = objectMapper.readValue(result, AttemptPendingStore.MAP_TYPE)
        val pending = parsed["pending"] ?: return null
        objectMapper.convertValue(pending, T::class.java)
    } catch (e: Exception) {
        null
    }
}
