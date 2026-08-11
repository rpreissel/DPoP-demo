package com.example.dpop.orchestrator.registration;

import com.example.dpop.ext_stammdaten.Person;
import com.example.dpop.ext_stammdaten.PersonRepository;
import com.example.dpop.id_fsc.IdFscService;
import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.NextStep;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/registration-sessions")
public class RegistrationController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final RegistrationSessionService sessionService;
    private final PersonRepository personRepository;
    private final IdFscService idFscService;

    public RegistrationController(DpopValidator dpopValidator,
                                  JwkThumbprintService jwkThumbprintService,
                                  RegistrationSessionService sessionService,
                                  PersonRepository personRepository,
                                  IdFscService idFscService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.sessionService = sessionService;
        this.personRepository = personRepository;
        this.idFscService = idFscService;
    }

    @PostMapping
    public ResponseEntity<RegistrationSetupResponse> setup(
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        UUID sessionId = sessionService.getOrCreateSession(thumbprint);

        NextStep nextStep = new NextStep.UseIdentificationMethodNextStep(List.of("fsc"));
        return ResponseEntity.ok(new RegistrationSetupResponse(sessionId, nextStep));
    }

    @PostMapping("/{registrationSessionId}/identification-methods/fsc")
    public ResponseEntity<RegistrationSetupResponse> startFscIdentification(
            @PathVariable UUID registrationSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody FscIdentificationRequest requestBody,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        sessionService.requireSession(registrationSessionId, thumbprint);

        Person person = personRepository.findByKvnr(requestBody.kvnr())
                .orElseThrow(() -> new RegistrationSessionException("Person with given KVNR not found"));

        if (!person.getName().equals(requestBody.name()) || !person.getVorname().equals(requestBody.vorname())) {
            throw new RegistrationSessionException("Person data does not match");
        }

        sessionService.setPersonId(registrationSessionId, thumbprint, person.getId());

        NextStep nextStep = new NextStep.FscInputNextStep();
        return ResponseEntity.ok(new RegistrationSetupResponse(registrationSessionId, nextStep));
    }

    @PatchMapping("/{registrationSessionId}/identification-methods/fsc")
    public ResponseEntity<RegistrationSetupResponse> submitFsc(
            @PathVariable UUID registrationSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody FscInputRequest requestBody,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        ClientSession session = sessionService.requireSession(registrationSessionId, thumbprint);

        Long personId = session.getPersonId();
        if (personId == null) {
            throw new RegistrationSessionException("No person selected for this session");
        }

        String fscCode = requestBody.fsc();
        if (fscCode == null || fscCode.isBlank()) {
            throw new RegistrationSessionException("FSC code is required");
        }

        if (!idFscService.validateFsc(personId, fscCode)) {
            throw new RegistrationSessionException("Invalid or expired FSC code");
        }

        NextStep nextStep = new NextStep.AuthenticationSetupNextStep(List.of("sms"));
        return ResponseEntity.ok(new RegistrationSetupResponse(registrationSessionId, nextStep));
    }

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RegistrationSessionException.class)
    public ResponseEntity<Map<String, String>> handleSessionException(RegistrationSessionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    private String buildRequestUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String path = request.getRequestURI();

        StringBuilder url = new StringBuilder(scheme).append("://").append(host);
        if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
            url.append(":").append(port);
        }
        url.append(path);
        return url.toString();
    }
}
