package com.example.dpop.ext_stammdaten;

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
        return personRepository.findByKvnr(kvnr).map(Person::getId);
    }
}
