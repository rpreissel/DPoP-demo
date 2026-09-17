package com.example.dpop.demo_seed.internal

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.resolveAccountByPersonId
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.EnrollmentRef
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Demo-only: gives every V2__testdata.sql test person a real orchestrator account with one active
 * step-up method, so the `keycloak` profile's native-password/orchestrator-step-up flow
 * (Keycloak-eigene LoA-Subflow-Konfiguration, infra/tofu/keycloak's LoA-1/LoA-2 split) has something real
 * to demonstrate on first boot - LoA1 is native Keycloak password now, so the orchestrator itself
 * never establishes these accounts' identity; something has to.
 *
 * Calls [AccountService.recordClaim] and `PasswordCredentialPort.setNew` directly - both are
 * public API of a module this one is allowed to depend on (see this module's own
 * `ModuleMetadata`); there is no method-level access control in Spring Modulith, only the
 * package/module boundary `DpopApplicationTests.modulithStructureIsValid` checks. The seeded
 * PERSON_ID/EMAIL claims use `ClaimSource.DEMO_BOOTSTRAP` (docs/ideen/account-attribute-und-
 * trust-vereinheitlichen.md, Paket 6) - the same claims-/anchor-log path every real IDENT/
 * enrollment run goes through, never a fabricated FSC/eID/email-tool run.
 *
 * Idempotent by construction (`accountId`/`personId` come from PersonDirectory - see
 * infra/tofu/keycloak/main.tf's `orchestratorAccountId` comment for why person insertion order
 * matters here): re-running on an existing DB skips any person who already has an active "email"
 * (or "password") method instead of piling up deactivated duplicates on every restart.
 */
@Component
@Profile("keycloak")
internal class KcDemoAccountSeeder(
    private val personDirectory: PersonDirectory,
    private val accountService: AccountService,
    private val passwordCredentialPort: PasswordCredentialPort
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(KcDemoAccountSeeder::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        TEST_PERSONS.forEach { person ->
            val personId = personDirectory.findPersonIdByKvnr(person.kvnr)
            if (personId == null) {
                log.warn("kc demo seed: no person found for kvnr {} - skipping", person.kvnr)
                return@forEach
            }
            val existingId = accountService.resolveAccountByPersonId(personId)
            val profile = if (existingId == null) accountService.createUnidentifiedAccount()
                else checkNotNull(accountService.findAccount(existingId)) { "Account not found: $existingId" }
            if (accountService.anchorValue(profile.accountId, AttributeType.PERSON_ID) == null) {
                accountService.recordClaim(
                    profile.accountId,
                    Claim(AttributeType.PERSON_ID, personId.toString(), ClaimSource.DEMO_BOOTSTRAP)
                )
            }
            if (profile.activeAuthenticationMethods.none { it.method == "email" }) {
                accountService.recordClaim(
                    profile.accountId,
                    Claim(AttributeType.EMAIL, person.email, ClaimSource.DEMO_BOOTSTRAP)
                )
                accountService.addAuthenticationMethod(
                    profile.accountId,
                    "email",
                    EnrollmentRef(type = "account_email", id = "self"),
                    enrolledUnderAcr = "loa1",
                    details = emptyMap()
                )
            }
            if (profile.activeAuthenticationMethods.none { it.method == "password" }) {
                val enrollmentRef = passwordCredentialPort.setNew(DEMO_PASSWORD)
                accountService.addAuthenticationMethod(
                    profile.accountId,
                    "password",
                    enrollmentRef,
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
        // Demo-only shared default - a real onboarding flow would never hand out a shared
        // password.
        // Same literal as auth_password's own DEMO_PASSWORD (DemoPassword.kt) - one demo password
        // project-wide; duplicated rather than imported since this module may not depend on
        // auth_password directly (module boundary).
        private const val DEMO_PASSWORD = "Demo1234!"

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
