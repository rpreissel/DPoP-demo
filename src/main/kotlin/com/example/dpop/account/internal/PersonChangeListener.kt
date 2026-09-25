package com.example.dpop.account.internal

import org.springframework.scheduling.annotation.Async
import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonChanged
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * The account side of a change in the Personenverzeichnis (ADR-34): the account bound to that
 * person follows it, and its `AccountChanged` - naming what changed - lets the Keycloak sync
 * follow in turn. An [ApplicationModuleListener], so the change sits in the Event Publication
 * Registry until this ran through: a failure is retried, never lost (ADR-29).
 */
@Component
class PersonChangeListener(private val accountService: AccountService) {

    /**
     * On one lane ([PERSON_CHANGE_EXECUTOR]), not Spring's shared async pool: two changes to the
     * same person (a Versicherungsnummer changed twice) must be applied in the order the directory
     * published them - the Event Publication Registry guarantees delivery, not order.
     */
    @ApplicationModuleListener
    @Async(PERSON_CHANGE_EXECUTOR)
    fun onPersonChanged(event: PersonChanged) {
        accountService.applyDirectoryChange(event)
    }
}
