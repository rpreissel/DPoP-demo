package com.example.dpop.ext_personenverzeichnis.internal

import org.springframework.data.jpa.repository.JpaRepository

interface PersonRepository : JpaRepository<Person, String> {
    fun findByKvnr(kvnr: String): Person?
    fun findByVersnr(versnr: String): Person?
}
