package com.example.dpop.id_fsc.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface FscCodeRepository : JpaRepository<FscCode, Long> {
    fun findByPersonId(personId: Long): Optional<FscCode>
    fun findByPersonIdAndCode(personId: Long, code: String): Optional<FscCode>
}
