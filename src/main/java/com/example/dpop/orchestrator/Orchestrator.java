package com.example.dpop.orchestrator;

import com.example.dpop.account.AccountService;
import com.example.dpop.auth_sms.AuthSmsService;
import com.example.dpop.ext_stammdaten.ExtStammdatenService;
import com.example.dpop.id_fsc.IdFscService;
import org.springframework.stereotype.Service;

@Service
public class Orchestrator {

    private final IdFscService idFscService;
    private final AuthSmsService authSmsService;
    private final AccountService accountService;
    private final ExtStammdatenService extStammdatenService;

    public Orchestrator(IdFscService idFscService,
                        AuthSmsService authSmsService,
                        AccountService accountService,
                        ExtStammdatenService extStammdatenService) {
        this.idFscService = idFscService;
        this.authSmsService = authSmsService;
        this.accountService = accountService;
        this.extStammdatenService = extStammdatenService;
    }

    public String runProcess() {
        return String.join(" | ",
                idFscService.identify(),
                authSmsService.setupSms("+49 170 1234567").phoneNumber(),
                accountService.manageAccount(),
                extStammdatenService.fetchStammdaten());
    }
}
