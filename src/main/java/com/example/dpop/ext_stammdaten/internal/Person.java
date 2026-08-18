package com.example.dpop.ext_stammdaten.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kvnr", unique = true, nullable = false, length = 20)
    private String kvnr;

    private String name;
    private String vorname;
    private String strasse;
    private String hausnummer;
    private String plz;
    private String ort;

    protected Person() {
    }

    public Person(String kvnr, String name, String vorname, String strasse, String hausnummer, String plz, String ort) {
        this.kvnr = kvnr;
        this.name = name;
        this.vorname = vorname;
        this.strasse = strasse;
        this.hausnummer = hausnummer;
        this.plz = plz;
        this.ort = ort;
    }

    public Long getId() {
        return id;
    }

    public String getKvnr() {
        return kvnr;
    }

    public String getName() {
        return name;
    }

    public String getVorname() {
        return vorname;
    }

    public String getStrasse() {
        return strasse;
    }

    public String getHausnummer() {
        return hausnummer;
    }

    public String getPlz() {
        return plz;
    }

    public String getOrt() {
        return ort;
    }
}
