package com.example.dpop.ext_stammdaten.internal

import org.springframework.data.jpa.repository.JpaRepository

interface PersonRepository : JpaRepository<Person, Long> {
    fun findByKvnr(kvnr: String): Person?
}
