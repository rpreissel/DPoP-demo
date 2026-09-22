package com.example.dpop.demo_seed.internal

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.SmsCredentialPort
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor

@SpringBootTest
@ActiveProfiles("test")
class KcDemoAccountSeederTest(
    accountService: AccountService,
    jdbc: JdbcTemplate,
    txManager: PlatformTransactionManager
) : BehaviorSpec({
    beforeEach {
        listOf("account.account").forEach { jdbc.update("DELETE FROM $it") }
    }

    fun seed(
        persons: PersonDirectory,
        passwords: PasswordCredentialPort,
        sms: SmsCredentialPort
    ): ApplicationRunner {
        val advice = TransactionInterceptor().apply {
            setTransactionManager(txManager)
            transactionAttributeSource = AnnotationTransactionAttributeSource()
        }
        return ProxyFactory(KcDemoAccountSeeder(persons, accountService, passwords, sms)).apply {
            addAdvice(advice)
        }.proxy as ApplicationRunner
    }

    given("the demo seed using the real annotation-driven transaction") {
        then("a restart reuses person anchors without duplicate claims or methods") {
            val persons = mockk<PersonDirectory>()
            val passwords = mockk<PasswordCredentialPort>()
            val sms = mockk<SmsCredentialPort>()
            listOf("A123456789", "B987654321", "C111111111").forEachIndexed { index, kvnr ->
                every { persons.findPersonIdByKvnr(kvnr) } returns index + 1L
            }
            every { passwords.setNew(any()) } returns EnrollmentRef("password", "demo")
            every { sms.enroll(any()) } answers { EnrollmentRef("auth_sms.enrollment", firstArg<String>()) }
            val runner = seed(persons, passwords, sms)
            runner.run(DefaultApplicationArguments())
            val ids = accountService.allAccountIds().sorted()
            ids.size shouldBe 3
            (1L..3L).map { accountService.resolveByAnchor(AttributeType.PERSON_ID, it.toString()) } shouldBe ids
            // PERSON_ID, EMAIL and PHONE_NUMBER per person - but only the first two are local
            // anchors; PHONE_NUMBER is AttributeAuthority.MethodModule and stays claim-log only.
            jdbc.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 9
            jdbc.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 6

            runner.run(DefaultApplicationArguments())
            accountService.allAccountIds().sorted() shouldBe ids
            jdbc.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 9
            jdbc.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 6
            // password (KNOWLEDGE) + sms (POSSESSION), beide unter loa2 eingerichtet: Der Seed
            // vertritt eine abgeschlossene Identifizierung, und ein Enrollment wird mit dem
            // bezahlt, was die Sitzung dabei bewiesen hatte (ADR-5). Mit loa1 waere die
            // Kombination der beiden Faktoren durch maxEnrolledUnderAcr gedeckelt
            // (DefaultAuthPolicy.combinedAcr) - das Demo-Konto kaeme nie auf loa2, obwohl genau
            // dafuer zwei Faktorarten geseedet werden.
            ids.forEach { profileId ->
                val methods = accountService.findAccount(profileId)?.activeAuthenticationMethods.orEmpty()
                methods.map { it.method }.sorted() shouldBe listOf("password", "sms")
                methods.map { it.enrolledUnderAcr }.toSet() shouldBe setOf("loa2")
            }
            verify(exactly = 3) { passwords.setNew(any()) }
            verify(exactly = 3) { sms.enroll(any()) }
        }

        then("a half-registered Interessent holding a person's email is left untouched, not adopted") {
            val persons = mockk<PersonDirectory>()
            val passwords = mockk<PasswordCredentialPort>()
            val sms = mockk<SmsCredentialPort>()
            listOf("A123456789", "B987654321", "C111111111").forEachIndexed { index, kvnr ->
                every { persons.findPersonIdByKvnr(kvnr) } returns index + 1L
            }
            every { passwords.setNew(any()) } returns EnrollmentRef("password", "demo")
            every { sms.enroll(any()) } answers { EnrollmentRef("auth_sms.enrollment", firstArg<String>()) }
            // A demo registration against the prefilled demo address left an Interessent that
            // holds person 1's email anchor but no PERSON_ID (KVNR correlation never ran). The
            // seed must not complete that account - person 1 is skipped wholesale, and only
            // persons 2 and 3 get seeded.
            val interessentId = accountService.createUnidentifiedAccount().accountId
            accountService.recordClaim(
                interessentId,
                Claim(AttributeType.EMAIL, "max.mustermann@example.com", ClaimSource.DEMO_BOOTSTRAP),
                provenAcr = AcrLevel.LOA2
            )
            seed(persons, passwords, sms).run(DefaultApplicationArguments())
            accountService.allAccountIds().size shouldBe 3
            // The Interessent keeps its email anchor and gains nothing: no PERSON_ID, no methods.
            accountService.resolveByAnchor(AttributeType.PERSON_ID, "1") shouldBe null
            accountService.resolveByAnchor(AttributeType.EMAIL, "max.mustermann@example.com") shouldBe interessentId
            accountService.findAccount(interessentId)?.activeAuthenticationMethods shouldBe emptyList()
            verify(exactly = 2) { passwords.setNew(any()) }
            verify(exactly = 2) { sms.enroll(any()) }
        }

        then("accounts holding the person's anchors are left untouched even in a forked state") {
            val persons = mockk<PersonDirectory>()
            val passwords = mockk<PasswordCredentialPort>()
            val sms = mockk<SmsCredentialPort>()
            listOf("A123456789", "B987654321", "C111111111").forEachIndexed { index, kvnr ->
                every { persons.findPersonIdByKvnr(kvnr) } returns index + 1L
            }
            every { passwords.setNew(any()) } returns EnrollmentRef("password", "demo")
            every { sms.enroll(any()) } answers { EnrollmentRef("auth_sms.enrollment", firstArg<String>()) }
            // The forked state: person 1 already has an account via its PERSON_ID anchor, but
            // the email anchor sits on a different account. The seed skips person 1 at the
            // PERSON_ID hit alone - neither account is modified, no third one is created.
            val person1Account = accountService.createUnidentifiedAccount().accountId
            accountService.recordClaim(
                person1Account,
                Claim(AttributeType.PERSON_ID, "1", ClaimSource.DEMO_BOOTSTRAP),
                provenAcr = AcrLevel.LOA2
            )
            val foreignId = accountService.createUnidentifiedAccount().accountId
            accountService.recordClaim(
                foreignId,
                Claim(AttributeType.EMAIL, "max.mustermann@example.com", ClaimSource.DEMO_BOOTSTRAP),
                provenAcr = AcrLevel.LOA2
            )
            seed(persons, passwords, sms).run(DefaultApplicationArguments())
            accountService.allAccountIds().size shouldBe 4
            accountService.resolveByAnchor(AttributeType.PERSON_ID, "1") shouldBe person1Account
            accountService.resolveByAnchor(AttributeType.EMAIL, "max.mustermann@example.com") shouldBe foreignId
            accountService.anchorValue(person1Account, AttributeType.EMAIL) shouldBe null
            // Untouched means exactly that: no methods materialize on the pre-existing account.
            accountService.findAccount(person1Account)?.activeAuthenticationMethods shouldBe emptyList()
        }

        then("a failure after claim acceptance leaves no partial seed accounts") {
            val persons = mockk<PersonDirectory>()
            val passwords = mockk<PasswordCredentialPort>()
            val sms = mockk<SmsCredentialPort>()
            every { persons.findPersonIdByKvnr("A123456789") } returns 1L
            every { sms.enroll(any()) } answers { EnrollmentRef("auth_sms.enrollment", firstArg<String>()) }
            every { passwords.setNew(any()) } throws IllegalStateException("Demo password creation failed")
            shouldThrow<IllegalStateException> {
                seed(persons, passwords, sms).run(DefaultApplicationArguments())
            }
            accountService.allAccountIds() shouldBe emptyList()
            jdbc.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 0
            jdbc.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 0
        }
    }
})
