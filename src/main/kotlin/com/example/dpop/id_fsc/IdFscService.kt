package com.example.dpop.id_fsc

import com.example.dpop.id_fsc.internal.FscCode
import com.example.dpop.id_fsc.internal.FscCodeRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class IdFscService(private val fscCodeRepository: FscCodeRepository) {

    fun identify(): String = "id_fsc: identity verified"

    fun hasValidFsc(personId: Long): Boolean =
        fscCodeRepository.findByPersonId(personId)?.isValid ?: false

    fun validateFsc(personId: Long, code: String): Boolean =
        fscCodeRepository.findByPersonIdAndCode(personId, code)?.isValid ?: false

    fun createFsc(personId: Long, code: String, expiresAt: Instant) {
        fscCodeRepository.save(FscCode(personId, code, expiresAt))
    }
}
