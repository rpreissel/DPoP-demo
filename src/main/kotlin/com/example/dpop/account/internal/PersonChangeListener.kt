package com.example.dpop.account.internal

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

    @ApplicationModuleListener
    fun onPersonChanged(event: PersonChanged) {
        accountService.applyDirectoryChange(event)
    }
}
