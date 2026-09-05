package com.example.dpop.auth_email.internal.kcdemo

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_spi.EnrollmentRef
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Demo-only: gives every V2__testdata.sql test person a real orchestrator account with one active
 * step-up method, so the `keycloak` profile's native-password/orchestrator-step-up flow
 * (docs/ideen/web-keycloak-kanal.md #9, infra/tofu/keycloak's LoA-1/LoA-2 split) has something real
 * to demonstrate on first boot - LoA1 is native Keycloak password now, so the orchestrator itself
 * never establishes these accounts' identity; something has to.
 *
 * Lives under `auth_email` (not a new top-level class) because [AccountService.confirmEmail] is
 * deliberately restricted to this module (see its own ModuleMetadata) - seeding through it keeps
 * that boundary intact instead of adding a second, undeclared caller.
 *
 * Idempotent by construction (`accountId`/`personId` come from PersonDirectory - see
 * infra/tofu/keycloak/main.tf's `orchestratorAccountId` comment for why person insertion order
 * matters here): re-running on an existing DB skips any person who already has an active "email"
 * method instead of piling up deactivated duplicates on every restart.
 */
@Component
@Profile("keycloak")
internal class KcDemoAccountSeeder(
    private val personDirectory: PersonDirectory,
    private val accountService: AccountService
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(KcDemoAccountSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        TEST_PERSONS.forEach { person ->
            val personId = personDirectory.findPersonIdByKvnr(person.kvnr)
            if (personId == null) {
                log.warn("kc demo seed: no person found for kvnr {} - skipping", person.kvnr)
                return@forEach
            }
            val profile = accountService.findOrCreateAccount(personId)
            if (profile.activeAuthenticationMethods.none { it.method == "email" }) {
                accountService.confirmEmail(profile.accountId, person.email)
                accountService.addAuthenticationMethod(
                    profile.accountId,
                    "email",
                    EnrollmentRef(type = "account_email", id = "self"),
                    enrolledUnderAcr = "loa1",
                    details = emptyMap()
                )
            }
            log.info(
                "kc demo seed: {} -> orchestrator accountId={} (Keycloak user attribute orchestratorAccountId, see infra/tofu/keycloak/main.tf)",
                person.kvnr, profile.accountId
            )
        }
    }

    private data class TestPerson(val kvnr: String, val email: String)

    companion object {
        // Same three persons/kvnrs as V2__testdata.sql - kept in that exact order because a fresh
        // DB assigns account ids sequentially in the order accounts are first created, and
        // infra/tofu/keycloak/main.tf's keycloak_user resources hardcode the resulting ids
        // (1/2/3) as each user's orchestratorAccountId attribute.
        private val TEST_PERSONS = listOf(
            TestPerson("A123456789", "max.mustermann@example.com"),
            TestPerson("B987654321", "erika.beispiel@example.com"),
            TestPerson("C111111111", "jane.doe@example.com")
        )
    }
}
