package com.example.dpop.ext_stammdaten.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, Long> {

    Optional<Person> findByKvnr(String kvnr);
}
