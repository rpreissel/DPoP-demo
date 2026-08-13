package com.example.dpop.orchestrator.authorisation;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.auth_sms.AuthSmsSetupResult;
import com.example.dpop.orchestrator.dpop.DpopProof;
import com.example.dpop.orchestrator.dpop.DpopValidationException;
import com.example.dpop.orchestrator.dpop.DpopValidator;
import com.example.dpop.orchestrator.dpop.JwkThumbprintService;
import com.example.dpop.orchestrator.registration.RegistrationSessionException;
import com.example.dpop.orchestrator.session.ClientSession;
import com.example.dpop.orchestrator.session.NextStep;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orchestrator/authorisation-sessions")
public class AuthorisationController {

    private final DpopValidator dpopValidator;
    private final JwkThumbprintService jwkThumbprintService;
    private final AuthorisationSessionService sessionService;
    private final AccountService accountService;
    private final AuthSmsService authSmsService;

    public AuthorisationController(DpopValidator dpopValidator,
                                   JwkThumbprintService jwkThumbprintService,
                                   AuthorisationSessionService sessionService,
                                   AccountService accountService,
                                   AuthSmsService authSmsService) {
        this.dpopValidator = dpopValidator;
        this.jwkThumbprintService = jwkThumbprintService;
        this.sessionService = sessionService;
        this.accountService = accountService;
        this.authSmsService = authSmsService;
    }

    @PostMapping("/{authorisationSessionId}/authentication-methods/sms/challenge")
    public ResponseEntity<AuthorisationSetupResponse> startSmsChallenge(
            @PathVariable UUID authorisationSessionId,
            @RequestHeader("DPoP") String dpopProof,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        ClientSession session = sessionService.requireSession(authorisationSessionId, thumbprint);

        Long accountId = session.getAccountId();
        if (accountId == null) {
            throw new RegistrationSessionException("No account linked to this authorisation session");
        }

        String phoneNumber = accountService.findActiveSmsPhoneNumber(accountId)
                .orElseThrow(() -> new RegistrationSessionException("No active sms authentication method configured"));

        AuthSmsSetupResult smsResult = authSmsService.setupSms(phoneNumber);

        NextStep nextStep = new NextStep.SmsTanInputNextStep(smsResult.smsSetupId(), smsResult.tan());
        return ResponseEntity.ok(new AuthorisationSetupResponse(nextStep));
    }

    @PostMapping("/{authorisationSessionId}/authentication-methods/sms/verify-tan")
    public ResponseEntity<AuthorisationSetupResponse> verifySmsTan(
            @PathVariable UUID authorisationSessionId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody SmsVerifyRequest requestBody,
            HttpServletRequest request) {

        String requestUrl = buildRequestUrl(request);
        DpopProof proof = dpopValidator.validate(dpopProof, request.getMethod(), requestUrl);
        String thumbprint = jwkThumbprintService.computeThumbprint(proof.publicKey());
        sessionService.requireSession(authorisationSessionId, thumbprint);

        authSmsService.validateTan(requestBody.smsSetupId(), requestBody.tan());

        NextStep nextStep = new NextStep.AuthenticationCompletedNextStep();
        return ResponseEntity.ok(new AuthorisationSetupResponse(nextStep));
    }

    @ExceptionHandler(DpopValidationException.class)
    public ResponseEntity<Map<String, String>> handleDpopValidation(DpopValidationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RegistrationSessionException.class)
    public ResponseEntity<Map<String, String>> handleSessionException(RegistrationSessionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
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
