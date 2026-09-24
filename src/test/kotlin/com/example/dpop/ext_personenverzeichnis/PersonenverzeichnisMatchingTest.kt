package com.example.dpop.ext_personenverzeichnis

import com.example.dpop.ext_personenverzeichnis.internal.MrzName
import com.example.dpop.ext_personenverzeichnis.internal.Person
import com.example.dpop.ext_personenverzeichnis.internal.PersonRepository
import com.example.dpop.tool_api.ClaimedIdentity
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional

/**
 * How the register compares what a document attests with what it has on file: names in their
 * passport (MRZ) form, the street as one line against its own two fields.
 */
class PersonenverzeichnisMatchingTest : BehaviorSpec({

    val geburtsdatum = LocalDate.of(1980, 2, 29)

    fun registerWith(person: Person): Personenverzeichnis {
        val repository = mockk<PersonRepository>()
        every { repository.findById("P000000001") } returns Optional.of(person)
        return Personenverzeichnis(repository, mockk(relaxed = true))
    }

    given("MRZ form") {
        then("it spells out umlauts, drops other diacritics and joins parts with <") {
            MrzName.of("Müller-Lüdenscheidt") shouldBe "MUELLER<LUEDENSCHEIDT"
            MrzName.of(" Straße ") shouldBe "STRASSE"
            MrzName.of("José María") shouldBe "JOSE<MARIA"
            MrzName.of("O'Brien") shouldBe "O<BRIEN"
        }
    }

    given("Jürgen Müller-Lüdenscheidt on file") {
        val register = registerWith(
            Person(kvnr = "A123456789", name = "Müller-Lüdenscheidt", vorname = "Jürgen", strasse = "Heidestraße", hausnummer = "17", geburtsdatum = geburtsdatum)
        )

        then("a passport chip's reading matches") {
            register.matchesStammdaten("P000000001", ClaimedIdentity(name = "MUELLER LUEDENSCHEIDT", vorname = "JUERGEN", geburtsdatum = geburtsdatum)) shouldBe true
        }

        then("an eID card's upper-case reading matches, street line included") {
            register.matchesStammdaten(
                "P000000001",
                ClaimedIdentity(name = "MÜLLER-LÜDENSCHEIDT", vorname = "JÜRGEN", geburtsdatum = geburtsdatum, strasse = "HEIDESTRASSE 17")
            ) shouldBe true
        }

        then("a different person does not") {
            register.matchesStammdaten("P000000001", ClaimedIdentity(name = "MUELLER", vorname = "JUERGEN", geburtsdatum = geburtsdatum)) shouldBe false
            register.matchesStammdaten("P000000001", ClaimedIdentity(name = "Müller-Lüdenscheidt", vorname = "Jürgen", strasse = "Heidestraße 18")) shouldBe false
        }

        then("typed Personalien compare the same way") {
            register.matchesPersonalien("P000000001", "mueller-luedenscheidt", "juergen", geburtsdatum) shouldBe true
        }
    }

    given("a name longer than a passport's name field") {
        val register = registerWith(
            Person(kvnr = "A123456789", name = "Schmidt-Wolkenstein", vorname = "Maximilian Friedrich Alexander", geburtsdatum = geburtsdatum)
        )

        then("the chip's truncated reading still matches - the register asks no more than the chip holds") {
            // SCHMIDT<WOLKENSTEIN<<MAXIMILIAN<FRIEDRI = 39 characters
            register.matchesStammdaten("P000000001", ClaimedIdentity(name = "SCHMIDT WOLKENSTEIN", vorname = "MAXIMILIAN FRIEDRI", geburtsdatum = geburtsdatum)) shouldBe true
        }
    }
})
