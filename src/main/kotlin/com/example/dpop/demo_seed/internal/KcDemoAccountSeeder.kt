package com.example.dpop.demo_seed.internal

import com.example.dpop.account.AccountService
import com.example.dpop.demo_seed.DemoAccountSeed
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.QrCredentialPort
import com.example.dpop.tool_api.SmsCredentialPort
import com.example.dpop.tool_api.resolveAccountByEmail
import com.example.dpop.tool_api.resolveAccountByPersonId
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Demo-only: gives every demo_seed/V16__testdata.sql test person a real orchestrator account with a
 * confirmed address and three active login methods - `password` (KNOWLEDGE) and `sms` (POSSESSION),
 * the pair a step-up to LoA2 can actually combine, plus the `qr` opt-in for the web channel's QR
 * login - so the `keycloak` profile's
 * native-password/orchestrator-step-up flow
 * (Keycloak-eigene LoA-Subflow-Konfiguration, infra/tofu/keycloak's LoA-1/LoA-2 split) has something real
 * to demonstrate on first boot - LoA1 is native Keycloak password now, so the orchestrator itself
 * never establishes these accounts' identity; something has to.
 *
 * Calls [AccountService.recordClaim], `PasswordCredentialPort.setNew` and
 * `SmsCredentialPort.enroll` directly - all are public API of a module this one is allowed to
 * depend on (see this module's own
 * `ModuleMetadata`); there is no method-level access control in Spring Modulith, only the
 * package/module boundary `DpopApplicationTests.modulithStructureIsValid` checks. The seeded
 * PERSON_ID/EMAIL/PHONE_NUMBER claims use `ClaimSource.DEMO_BOOTSTRAP` (docs/ideen/account-attribute-und-
 * trust-vereinheitlichen.md, Paket 6) - the same claims-/anchor-log path every real IDENT/
 * enrollment run goes through, never a fabricated FSC/eID/email-tool run.
 *
 * Idempotent by construction (`accountId`/`personId` come from PersonDirectory - see
 * infra/tofu/keycloak/main.tf's `orchestratorAccountId` comment for why person insertion order
 * matters here): the seed only ever CREATES, never modifies. A person whose PERSON_ID or EMAIL
 * anchor already resolves to an existing account - a previous seed run, or the half-registered
 * Interessent a demo registration against the prefilled demo address leaves behind - is skipped
 * entirely; that account keeps whatever state it has, completed or not. Completing it would mean
 * the seeder rewriting account state it does not own - removing or finishing such an account is
 * the operator's call, not the boot's.
 */
@Component
@Profile("keycloak")
internal class KcDemoAccountSeeder(
    private val personDirectory: PersonDirectory,
    private val accountService: AccountService,
    private val passwordCredentialPort: PasswordCredentialPort,
    private val smsCredentialPort: SmsCredentialPort,
    private val qrCredentialPort: QrCredentialPort
) : ApplicationRunner, DemoAccountSeed {

    private val log = LoggerFactory.getLogger(KcDemoAccountSeeder::class.java)

    // Transactional itself too: seed() called from here is a self-invocation that bypasses the
    // proxy, so without this the boot seed would run without the all-or-nothing transaction.
    @Transactional
    override fun run(args: ApplicationArguments) {
        seed()
    }

    @Transactional
    override fun seed(): Int {
        var created = 0
        TEST_PERSONS.forEach { person ->
            val personId = personDirectory.findPersonIdByKvnr(person.kvnr)
            if (personId == null) {
                log.warn("kc demo seed: no person found for kvnr {} - skipping", person.kvnr)
                return@forEach
            }
            // The seed only ever creates. An account that already holds one of this person's
            // anchors - the PERSON_ID of a previous seed or identification, or the email of a
            // half-registered Interessent (the frontend's person picker prefills these very
            // addresses) - is left exactly as it is; completing it would be the seeder modifying
            // account state it does not own (see class KDoc).
            val existingId = accountService.resolveAccountByPersonId(personId)
                ?: accountService.resolveAccountByEmail(person.email)
            if (existingId != null) {
                log.info(
                    "kc demo seed: account {} already holds an anchor of {} - leaving it untouched, not seeding",
                    existingId, person.kvnr
                )
                return@forEach
            }
            val profile = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                profile.accountId,
                Claim(AttributeType.PERSON_ID, personId, ClaimSource.DEMO_BOOTSTRAP),
                // The seed stands in for a completed identification, so it pays the same price
                // a real one would (AnchorRule.acrFloor) - stated here rather than waved
                // through, so the demo data is not held to a weaker rule than production.
                provenAcr = SEEDED_ACR
            )
            // The confirmed address itself, independent of any login method (ADR-17): it is the
            // account's EMAIL anchor, which the lookup logins resolve against. No other account
            // can hold it at this point - one that does was the skip case above.
            accountService.recordClaim(
                profile.accountId,
                Claim(AttributeType.EMAIL, person.email, ClaimSource.DEMO_BOOTSTRAP),
                provenAcr = SEEDED_ACR
            )
            // POSSESSION, to complement the password's KNOWLEDGE - the two factors a step-up
            // to loa2 needs to combine. No TAN was exchanged here, which is exactly why this
            // goes through the port's bootstrap-only entry point. Fresh account, so no
            // "already enrolled" guards either - existing accounts are skipped wholesale.
            val instanceId = UUID.randomUUID()
            val enrollmentRef = smsCredentialPort.enroll(person.phoneNumber)
            accountService.recordClaims(
                profile.accountId,
                listOf(Claim(AttributeType.PHONE_NUMBER, person.phoneNumber, ClaimSource.DEMO_BOOTSTRAP)),
                provenAcr = SEEDED_ACR,
                // The claim points at the method instance that established it (ADR-12), same
                // as the live enroll-sms path does.
                authMethodId = instanceId
            )
            // Beide unter SEEDED_ACR, nicht unter der loa1-Obergrenze des jeweiligen Tools: Ein
            // Enrollment wird mit dem bezahlt, was die Sitzung beim Einrichten bewiesen hatte
            // (ADR-5) - hier die Identifizierung, die dieser Seed vertritt. Unter loa1 waere ihre
            // Kombination durch maxEnrolledUnderAcr gedeckelt (DefaultAuthPolicy.combinedAcr) und
            // das Demo-Konto kaeme nie auf loa2.
            accountService.addAuthenticationMethod(
                profile.accountId,
                "sms",
                enrollmentRef,
                enrolledUnderAcr = SEEDED_ACR.value,
                details = emptyMap(),
                instanceId = instanceId
            )
            accountService.addAuthenticationMethod(
                profile.accountId,
                "password",
                passwordCredentialPort.setNew(DEMO_PASSWORD),
                enrolledUnderAcr = SEEDED_ACR.value,
                details = emptyMap()
            )
            // QR login opted in too, so the web channel's "Anmelden per QR-Code" works with a
            // demo account right away - approved from the app after logging in there.
            accountService.addAuthenticationMethod(
                profile.accountId,
                "qr",
                qrCredentialPort.optIn(),
                enrolledUnderAcr = SEEDED_ACR.value,
                details = emptyMap()
            )
            log.info(
                "kc demo seed: {} -> orchestrator accountId={} (Keycloak user attribute orchestratorAccountId, see infra/tofu/keycloak/main.tf)",
                person.kvnr, profile.accountId
            )
            created++
        }
        return created
    }

    private data class TestPerson(val kvnr: String, val email: String, val phoneNumber: String)

    companion object {
        // Demo-only shared default - a real onboarding flow would never hand out a shared
        // password.
        // Same literal as auth_password's own DEMO_PASSWORD (DemoPassword.kt) - one demo password
        // project-wide; duplicated rather than imported since this module may not depend on
        // auth_password directly (module boundary).
        private const val DEMO_PASSWORD = "Demo1234!"

        /**
         * What the seed claims to have proven. It stands in for a completed ident-fsc run
         * (`ClaimSource.DEMO_BOOTSTRAP`, rank PROVEN), so it pays the PERSON_ID anchor's own price
         * (`AnchorRule.acrFloor`) rather than being waved through - demo data must not be held to
         * a weaker rule than the flow it imitates. The same figure stamps the two credentials:
         * they are enrolled in that identification's own session, exactly as a real registration
         * would (`AccountFixtures.ENROLLED_UNDER_ACR` records the same, measured from a real run).
         */
        private val SEEDED_ACR = AcrLevel.LOA2

        // Same three persons/kvnrs as demo_seed/V16__testdata.sql - kept in that exact order because a fresh
        // DB assigns account ids sequentially in the order accounts are first created, and
        // infra/tofu/keycloak/main.tf's keycloak_user resources hardcode the resulting ids
        // (1/2/3) as each user's orchestratorAccountId attribute.
        private val TEST_PERSONS = listOf(
            TestPerson("A123456789", "max.mustermann@example.com", "+491700000001"),
            TestPerson("B987654321", "erika.beispiel@example.com", "+491700000002"),
            TestPerson("C111111111", "jane.doe@example.com", "+491700000003")
        )
    }
}
