package com.example.dpop.ext_stammdaten

import java.time.LocalDate

data class PersonData(
    val id: Long?,
    val kvnr: String?,
    val name: String?,
    val vorname: String?,
    val geburtsdatum: LocalDate?
)
