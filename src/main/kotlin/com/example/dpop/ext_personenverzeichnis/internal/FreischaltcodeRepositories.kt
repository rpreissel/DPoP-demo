package com.example.dpop.ext_personenverzeichnis.internal

import org.springframework.data.jpa.repository.JpaRepository

interface FreischaltcodeRepository : JpaRepository<Freischaltcode, Long> {
    fun findByPersonIdAndCodeHash(personId: String, codeHash: String): List<Freischaltcode>
    fun findByPersonIdOrderByIdDesc(personId: String): List<Freischaltcode>
}

interface BriefRepository : JpaRepository<Brief, Long> {
    fun findByPersonIdOrderByIdDesc(personId: String): List<Brief>
    fun findAllByOrderByIdDesc(): List<Brief>
}
