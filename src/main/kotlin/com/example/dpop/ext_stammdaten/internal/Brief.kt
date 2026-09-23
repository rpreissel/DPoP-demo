package com.example.dpop.ext_stammdaten.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The simulated letter that carries a Freischaltcode in plaintext to its person (ADR-31). */
@Entity
@Table(schema = "ext_stammdaten", name = "brief")
class Brief(
    @Column(name = "person_id", nullable = false)
    var personId: Long? = null,

    @Column(name = "freischaltcode_id", nullable = false)
    var freischaltcodeId: Long? = null,

    @Column(name = "code", nullable = false, length = 64)
    var code: String? = null,

    @Column(name = "versandt_am", nullable = false)
    var versandtAm: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
