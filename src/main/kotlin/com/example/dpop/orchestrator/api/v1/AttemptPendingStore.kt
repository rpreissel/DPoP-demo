package com.example.dpop.orchestrator.api.v1

import com.example.dpop.orchestrator.session.OrchestratorAttempt
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

@Component
class AttemptPendingStore(private val objectMapper: ObjectMapper) {

    fun <T> load(attempt: OrchestratorAttempt, type: Class<T>): T? {
        val result = attempt.result ?: return null
        if (result.isBlank()) return null
        return try {
            val parsed = objectMapper.readValue(result, MAP_TYPE)
            val pending = parsed["pending"] ?: return null
            objectMapper.convertValue(pending, type)
        } catch (e: Exception) {
            null
        }
    }

    fun save(attempt: OrchestratorAttempt, pending: Any) {
        try {
            attempt.result = objectMapper.writeValueAsString(mapOf("pending" to pending))
        } catch (e: Exception) {
            throw IllegalStateException("Pending-Daten konnten nicht serialisiert werden", e)
        }
    }

    companion object {
        private val MAP_TYPE = object : TypeReference<Map<String, Any>>() {}
    }
}
