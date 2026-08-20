package com.example.dpop.id_fsc.internal

import org.springframework.data.jpa.repository.JpaRepository

interface FscCodeRepository : JpaRepository<FscCode, Long> {
    fun findByPersonId(personId: Long): FscCode?
    fun findByPersonIdAndCode(personId: Long, code: String): FscCode?
}
