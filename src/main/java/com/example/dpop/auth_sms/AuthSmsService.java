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

    @Transactional
    public AuthSmsEnrollResult startEnrollment(String phoneNumber) {
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
        return new AuthSmsEnrollResult(new EnrollmentRef(setup.getId()), tan);
    }

    @Transactional
    public void confirmEnrollment(EnrollmentRef ref, String tan) {
        if (ref == null || tan == null || tan.isBlank()) {
            throw new IllegalArgumentException("ref und TAN sind erforderlich");
        }
        AuthSmsSetup setup = repository.findById(ref.id())
                .orElseThrow(() -> new IllegalArgumentException("SMS-Enrollment nicht gefunden: " + ref.id()));
        if (!setup.getTan().equals(tan.trim())) {
            throw new IllegalArgumentException("Ungueltige TAN");
        }
        setup.setValidated(true);
        setup.setUpdatedAt(Instant.now());
        repository.save(setup);
    }

    @Transactional
    public AuthSmsChallengeResult startChallenge(EnrollmentRef ref) {
        AuthSmsSetup setup = repository.findById(ref.id())
                .orElseThrow(() -> new IllegalArgumentException("SMS-Enrollment nicht gefunden: " + ref.id()));
        if (!setup.isValidated()) {
            throw new IllegalArgumentException("SMS-Enrollment wurde noch nicht validiert");
        }
        String tan = generateTan();
        setup.setTan(tan);
        setup.setUpdatedAt(Instant.now());
        repository.save(setup);
        sendTestSms(setup.getPhoneNumber(), tan);
        return new AuthSmsChallengeResult(ref, tan);
    }

    @Transactional
    public void verifyChallenge(EnrollmentRef ref, String tan) {
        if (ref == null || tan == null || tan.isBlank()) {
            throw new IllegalArgumentException("ref und TAN sind erforderlich");
        }
        AuthSmsSetup setup = repository.findById(ref.id())
                .orElseThrow(() -> new IllegalArgumentException("SMS-Enrollment nicht gefunden: " + ref.id()));
        if (!setup.getTan().equals(tan.trim())) {
            throw new IllegalArgumentException("Ungueltige TAN");
        }
    }

    public boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) return false;
        return PHONE_PATTERN.matcher(normalizePhoneNumber(phoneNumber)).matches();
    }

    private String normalizePhoneNumber(String phoneNumber) {
        return phoneNumber.replaceAll("\\s+", "").trim();
    }

    private String generateTan() {
        int tan = RANDOM.nextInt(900000) + 100000;
        return String.valueOf(tan);
    }

    private void sendTestSms(String phoneNumber, String tan) {
        System.out.println("[MOCK SMS] TAN " + tan + " an " + phoneNumber + " versandt.");
    }
}
