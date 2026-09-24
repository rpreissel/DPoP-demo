package com.example.dpop.ext_personenverzeichnis.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

@Entity
@Table(schema = "ext_personenverzeichnis", name = "person")
class Person(
    /** The Partnernummer (`tool_spi.Partnernr`) - handed out by the register itself, never a counter. */
    @Id
    @Column(name = "id", length = 10)
    var id: String? = null,
    /** Only for a person insured with us, i.e. with a [versnr] - but possibly missing for a while (ADR-34). */
    @Column(name = "kvnr", unique = true, length = 20)
    var kvnr: String? = null,
    /** Versicherungsnummer - only for a person insured with us; eight digits, unique when set. */
    @Column(name = "versnr", unique = true, length = 8)
    var versnr: String? = null,
    var name: String? = null,
    var vorname: String? = null,
    var strasse: String? = null,
    var hausnummer: String? = null,
    var plz: String? = null,
    var ort: String? = null,
    var geburtsdatum: LocalDate? = null
)
