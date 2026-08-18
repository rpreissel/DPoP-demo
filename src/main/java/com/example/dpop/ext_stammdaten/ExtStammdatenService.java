package com.example.dpop.ext_stammdaten;

import com.example.dpop.ext_stammdaten.internal.PersonRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ExtStammdatenService {

    private final PersonRepository personRepository;

    public ExtStammdatenService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public String fetchStammdaten() {
        var persons = personRepository.findAll();
        if (persons.isEmpty()) {
            return "ext_stammdaten: no persons found";
        }
        return "ext_stammdaten: " + persons.stream()
                .map(p -> p.getVorname() + " " + p.getName())
                .collect(Collectors.joining(", "));
    }

    public Optional<Long> findPersonIdByKvnr(String kvnr) {
        return personRepository.findByKvnr(kvnr).map(person -> person.getId());
    }

    public Optional<PersonData> findPersonByKvnr(String kvnr) {
        return personRepository.findByKvnr(kvnr)
                .map(person -> new PersonData(person.getId(), person.getKvnr(), person.getName(), person.getVorname()));
    }

    public Optional<PersonData> findPersonById(Long personId) {
        return personRepository.findById(personId)
                .map(person -> new PersonData(person.getId(), person.getKvnr(), person.getName(), person.getVorname()));
    }
}
