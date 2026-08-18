package com.example.dpop.id_fsc;

import com.example.dpop.id_fsc.internal.FscCodeRepository;
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
                .map(fscCode -> fscCode.isValid())
                .orElse(false);
    }

    public boolean validateFsc(Long personId, String code) {
        return fscCodeRepository.findByPersonIdAndCode(personId, code)
                .map(fscCode -> fscCode.isValid())
                .orElse(false);
    }

    public void createFsc(Long personId, String code, Instant expiresAt) {
        fscCodeRepository.save(new com.example.dpop.id_fsc.internal.FscCode(personId, code, expiresAt));
    }
}
