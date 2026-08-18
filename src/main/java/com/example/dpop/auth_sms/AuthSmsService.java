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
    public AuthSmsSetupResult setupSms(String phoneNumber) {
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

        boolean testSmsSent = sendTestSms(normalized, tan);

        return new AuthSmsSetupResult(setup.getId(), setup.getPhoneNumber(), setup.getTan(), testSmsSent);
    }

    @Transactional
    public AuthSmsSetupResult validateTan(Long smsSetupId, String tan) {
        if (smsSetupId == null || tan == null || tan.isBlank()) {
            throw new IllegalArgumentException("SMS-Setup-ID und TAN sind erforderlich");
        }

        AuthSmsSetup setup = repository.findById(smsSetupId)
                .orElseThrow(() -> new IllegalArgumentException("SMS-Setup nicht gefunden"));

        if (setup.isValidated()) {
            throw new IllegalArgumentException("TAN wurde bereits validiert");
        }

        if (!setup.getTan().equals(tan.trim())) {
            throw new IllegalArgumentException("Ungueltige TAN");
        }

        setup.setValidated(true);
        setup.setUpdatedAt(Instant.now());
        AuthSmsSetup validated = repository.save(setup);
        return new AuthSmsSetupResult(validated.getId(), validated.getPhoneNumber(), validated.getTan(), true);
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
