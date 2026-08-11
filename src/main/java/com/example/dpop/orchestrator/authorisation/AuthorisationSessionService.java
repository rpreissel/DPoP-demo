package com.example.dpop.orchestrator.authorisation;

import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.ClientSessionRepository;
import com.example.dpop.orchestrator.session.SessionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthorisationSessionService {

    private final ClientSessionRepository repository;

    public AuthorisationSessionService(ClientSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ClientSession> findByJwkThumbprint(String jwkThumbprint) {
        return repository.findByJwkThumbprintAndType(jwkThumbprint, SessionType.AUTH);
    }
}
