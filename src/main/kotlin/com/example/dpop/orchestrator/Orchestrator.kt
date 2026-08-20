package com.example.dpop.orchestrator

import com.example.dpop.account.AccountService
import com.example.dpop.auth_sms.AuthSmsService
import com.example.dpop.ext_stammdaten.ExtStammdatenService
import com.example.dpop.id_fsc.IdFscService
import org.springframework.stereotype.Service

@Service
class Orchestrator(
    private val idFscService: IdFscService,
    private val authSmsService: AuthSmsService,
    private val accountService: AccountService,
    private val extStammdatenService: ExtStammdatenService
) {
    fun runProcess(): String = listOf(
        idFscService.identify(),
        authSmsService.startEnrollment("+491701234567").enrollmentRef.id.toString(),
        accountService.manageAccount(),
        extStammdatenService.fetchStammdaten()
    ).joinToString(" | ")
}
