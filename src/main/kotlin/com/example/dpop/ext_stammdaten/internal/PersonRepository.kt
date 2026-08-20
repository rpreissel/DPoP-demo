package com.example.dpop.ext_stammdaten.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface PersonRepository : JpaRepository<Person, Long> {
    fun findByKvnr(kvnr: String): Optional<Person>
}
