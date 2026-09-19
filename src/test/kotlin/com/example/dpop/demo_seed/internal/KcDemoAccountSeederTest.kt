package com.example.dpop.demo_seed.internal

import com.example.dpop.account.AccountService
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.PersonDirectory
import com.example.dpop.tool_api.SmsCredentialPort
import com.example.dpop.tool_spi.AttributeType
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
            // anchors; PHONE_NUMBER is AttributeAuthority.METHOD_MODULE and stays claim-log only.
            jdbc.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 9
            jdbc.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 6

            runner.run(DefaultApplicationArguments())
            accountService.allAccountIds().sorted() shouldBe ids
            jdbc.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 9
            jdbc.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 6
            // password (KNOWLEDGE) + sms (POSSESSION)
            ids.forEach { profileId ->
                accountService.findAccount(profileId)?.activeAuthenticationMethods
                    ?.map { it.method }?.sorted() shouldBe listOf("password", "sms")
            }
            verify(exactly = 3) { passwords.setNew(any()) }
            verify(exactly = 3) { sms.enroll(any()) }
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
