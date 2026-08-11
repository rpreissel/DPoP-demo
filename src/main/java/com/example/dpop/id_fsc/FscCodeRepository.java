package com.example.dpop.id_fsc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FscCodeRepository extends JpaRepository<FscCode, Long> {

    Optional<FscCode> findByPersonId(Long personId);

    Optional<FscCode> findByPersonIdAndCode(Long personId, String code);
}
