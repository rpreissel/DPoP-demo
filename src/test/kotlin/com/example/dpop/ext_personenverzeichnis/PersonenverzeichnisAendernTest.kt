package com.example.dpop.ext_personenverzeichnis

import com.example.dpop.ext_personenverzeichnis.internal.Person
import com.example.dpop.ext_personenverzeichnis.internal.PersonRepository
import com.example.dpop.tool_api.PersonChanged
import com.example.dpop.tool_spi.AttributeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.util.Optional

/**
 * Changing a person in the Personenverzeichnis (ADR-34): KVNR and Versicherungsnummer are
 * changeable, both validated and unique, a KVNR only with a Versicherungsnummer; what really
 * changed goes out as [PersonChanged].
 */
class PersonenverzeichnisAendernTest : BehaviorSpec({

    fun max() = Person(kvnr = "A123456789", versnr = "10000001", name = "Muster", vorname = "Max",
        geburtsdatum = LocalDate.of(1985, 6, 15), strasse = "Musterstraße", hausnummer = "1", plz = "12345", ort = "Musterstadt").also { it.id = "P000000001" }

    fun data(p: Person) = PersonData(p.id, p.kvnr, p.name, p.vorname, p.geburtsdatum, p.strasse, p.hausnummer, p.plz, p.ort, p.versnr)

    fun fixture(person: Person, taken: Person? = null): Pair<Personenverzeichnis, ApplicationEventPublisher> {
        val repository = mockk<PersonRepository>()
        every { repository.findById("P000000001") } returns Optional.of(person)
        every { repository.findByKvnr(any()) } answers { taken?.takeIf { it.kvnr == firstArg() } }
        every { repository.findByVersnr(any()) } answers { taken?.takeIf { it.versnr == firstArg() } }
        val events = mockk<ApplicationEventPublisher>(relaxed = true)
        return Personenverzeichnis(repository, events) to events
    }

    given("a new person") {
        val repository = mockk<PersonRepository>()
        every { repository.findByKvnr(any()) } returns null
        every { repository.findByVersnr(any()) } returns null
        every { repository.existsById(any()) } returns false
        every { repository.save(any()) } answers { firstArg() }
        val verzeichnis = Personenverzeichnis(repository, mockk(relaxed = true))
        val paula = PersonData(null, null, "Schulz", "Paula", LocalDate.of(1982, 8, 8))

        then("a Partner needs neither number, and the register hands out the Partnernummer") {
            val angelegt = verzeichnis.anlegen(paula)
            angelegt.versnr shouldBe null
            angelegt.kvnr shouldBe null
            angelegt.id.shouldNotBeNull() shouldMatch Regex("^P\\d{9}$")
        }

        then("a KVNR alone is refused") {
            shouldThrow<PersonRejectedException> { verzeichnis.anlegen(paula.copy(kvnr = "C222222222")) }
        }
    }

    given("a person") {
        then("a new KVNR, Versicherungsnummer and address go out as one PersonChanged with their kinds and the new identifiers") {
            val person = max()
            val (verzeichnis, events) = fixture(person)
            val published = slot<Any>()
            every { events.publishEvent(capture(published)) } returns Unit

            verzeichnis.aendern("P000000001", data(person).copy(kvnr = "a111111111", versnr = "20000002", hausnummer = "2"))

            published.captured shouldBe PersonChanged("P000000001", setOf(AttributeType.KVNR, AttributeType.INSURANCE_NUMBER, AttributeType.STREET_ADDRESS), "A111111111", "20000002")
            person.kvnr shouldBe "A111111111"
        }

        then("no longer insured with us: both numbers go, the person stays as a Partner") {
            val person = max()
            val (verzeichnis, events) = fixture(person)
            verzeichnis.aendern("P000000001", data(person).copy(kvnr = "", versnr = ""))
            verify { events.publishEvent(PersonChanged("P000000001", setOf(AttributeType.KVNR, AttributeType.INSURANCE_NUMBER), null, null)) }
        }

        then("a KVNR may be missing for a while - the person stays insured") {
            val person = max()
            val (verzeichnis, events) = fixture(person)
            verzeichnis.aendern("P000000001", data(person).copy(kvnr = null))
            verify { events.publishEvent(PersonChanged("P000000001", setOf(AttributeType.KVNR), null, "10000001")) }
        }

        then("a KVNR without a Versicherungsnummer is refused - only an insured person has one") {
            val person = max()
            val (verzeichnis, events) = fixture(person)
            shouldThrow<PersonRejectedException> { verzeichnis.aendern("P000000001", data(person).copy(versnr = "")) }
            verify(exactly = 0) { events.publishEvent(any<Any>()) }
        }

        then("saving it unchanged announces nothing") {
            val person = max()
            val (verzeichnis, events) = fixture(person)
            verzeichnis.aendern("P000000001", data(person))
            verify(exactly = 0) { events.publishEvent(any<Any>()) }
        }

        then("a Versicherungsnummer that is not eight digits, or already someone else's, is refused") {
            val person = max()
            val other = Person(kvnr = "B987654321", versnr = "30000003").also { it.id = "P000000002" }
            val (verzeichnis, _) = fixture(person, taken = other)
            shouldThrow<PersonRejectedException> { verzeichnis.aendern("P000000001", data(person).copy(versnr = "1234")) }
            shouldThrow<PersonRejectedException> { verzeichnis.aendern("P000000001", data(person).copy(versnr = "30000003")) }
            shouldThrow<PersonRejectedException> { verzeichnis.aendern("P000000001", data(person).copy(kvnr = "B987654321")) }
        }
    }
})
