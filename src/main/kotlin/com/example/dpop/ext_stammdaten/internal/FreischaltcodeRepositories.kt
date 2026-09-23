package com.example.dpop.ext_stammdaten.internal

import org.springframework.data.jpa.repository.JpaRepository

interface FreischaltcodeRepository : JpaRepository<Freischaltcode, Long> {
    fun findByPersonIdAndCodeHash(personId: Long, codeHash: String): List<Freischaltcode>
    fun findByPersonIdOrderByIdDesc(personId: Long): List<Freischaltcode>
}

interface BriefRepository : JpaRepository<Brief, Long> {
    fun findByPersonIdOrderByIdDesc(personId: Long): List<Brief>
    fun findAllByOrderByIdDesc(): List<Brief>
}
