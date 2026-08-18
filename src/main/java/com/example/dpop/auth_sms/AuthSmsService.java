package com.example.dpop.auth_sms;

import com.example.dpop.auth_sms.internal.AuthSmsSetup;
import com.example.dpop.auth_sms.internal.AuthSmsSetupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.regex.Pattern;

@Service
public class AuthSmsService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{6,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSmsSetupRepository repository;

    public AuthSmsService(AuthSmsSetupRepository repository) {
        this.repository = repository;
    }

    /**
     * Enrolls a new SMS authentication method: validates the phone number, persists it,
     * sends a first TAN for confirmation, and returns the enrollment reference.
     */
    @Transactional
    public AuthSmsEnrollResult enrollSms(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Telefonnummer ist erforderlich");
        }
        String normalized = normalizePhoneNumber(phoneNumber);
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Ungueltige Telefonnummer");
        }

        String tan = generateTan();
        Instant now = Instant.now();
        AuthSmsSetup setup = repository.save(new AuthSmsSetup(normalized, tan, false, now, now));
        sendTestSms(normalized, tan);

        return new AuthSmsEnrollResult(setup.getId(), tan);
    }

    /**
     * Sends a fresh TAN to the phone number of an already validated enrollment.
     * The phone number stays within this module.
     */
    @Transactional
    public AuthSmsChallengeResult sendChallenge(Long enrollmentId) {
        AuthSmsSetup setup = repository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("SMS-Enrollment nicht gefunden: " + enrollmentId));
        if (!setup.isValidated()) {
            throw new IllegalArgumentException("SMS-Enrollment wurde noch nicht validiert");
        }
        String tan = generateTan();
        setup.setTan(tan);
        setup.setUpdatedAt(Instant.now());
        repository.save(setup);
        sendTestSms(setup.getPhoneNumber(), tan);
        return new AuthSmsChallengeResult(enrollmentId, tan);
    }

    /**
     * Validates a TAN for the given enrollment. Marks the enrollment as validated on success.
     */
    @Transactional
    public void validateTan(Long enrollmentId, String tan) {
        if (enrollmentId == null || tan == null || tan.isBlank()) {
            throw new IllegalArgumentException("enrollmentId und TAN sind erforderlich");
        }
        AuthSmsSetup setup = repository.findById(enrollmentId)
                .orElseThrow(() -> new IllegalArgumentException("SMS-Enrollment nicht gefunden: " + enrollmentId));

        if (!setup.getTan().equals(tan.trim())) {
            throw new IllegalArgumentException("Ungueltige TAN");
        }

        setup.setValidated(true);
        setup.setUpdatedAt(Instant.now());
        repository.save(setup);
    }

    public boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return false;
        }
        return PHONE_PATTERN.matcher(normalizePhoneNumber(phoneNumber)).matches();
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber.replaceAll("\\s+", "").trim();
    }

    private String generateTan() {
        int tan = RANDOM.nextInt(900000) + 100000;
        return String.valueOf(tan);
    }

    private boolean sendTestSms(String phoneNumber, String tan) {
        // Mocked SMS gateway: in production this would call an external provider.
        System.out.println("[MOCK SMS] TAN " + tan + " an " + phoneNumber + " versandt.");
        return true;
    }
}
