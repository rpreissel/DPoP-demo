package com.example.dpop.ext_stammdaten.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(name = "person")
class Person(
    @Column(name = "kvnr", unique = true, nullable = false, length = 20)
    var kvnr: String? = null,
    var name: String? = null,
    var vorname: String? = null,
    var strasse: String? = null,
    var hausnummer: String? = null,
    var plz: String? = null,
    var ort: String? = null,
    var geburtsdatum: LocalDate? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
