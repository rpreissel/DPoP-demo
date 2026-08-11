package com.example.dpop.id_fsc;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class IdFscService {

    private final FscCodeRepository fscCodeRepository;

    public IdFscService(FscCodeRepository fscCodeRepository) {
        this.fscCodeRepository = fscCodeRepository;
    }

    public String identify() {
        return "id_fsc: identity verified";
    }

    public boolean hasValidFsc(Long personId) {
        return fscCodeRepository.findByPersonId(personId)
                .map(FscCode::isValid)
                .orElse(false);
    }

    public boolean validateFsc(Long personId, String code) {
        return fscCodeRepository.findByPersonIdAndCode(personId, code)
                .map(FscCode::isValid)
                .orElse(false);
    }

    public FscCode createFsc(Long personId, String code, Instant expiresAt) {
        return fscCodeRepository.save(new FscCode(personId, code, expiresAt));
    }
}
