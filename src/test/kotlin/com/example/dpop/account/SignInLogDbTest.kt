package com.example.dpop.account

import com.example.dpop.account.infrastructure.SignInLogRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-39, addendum: the sign-in log belongs to the account - it goes with the account, and it is
 * kept for months, not years.
 */
@SpringBootTest
@ActiveProfiles("test")
class SignInLogDbTest(
    private val accountService: AccountService,
    private val signInLog: SignInLog,
    private val retention: SignInLogRetention,
    private val repository: SignInLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    beforeEach { jdbcTemplate.update("DELETE FROM account.account") }

    given("an account that signed in") {
        then("its sign-in log goes with it - unlike the change log, it is no proof beyond the deletion") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms", "password"), "FAST_ACCESS")
            signInLog.of(accountId).size shouldBe 1

            accountService.deleteAccount(accountId)

            repository.findByAccountIdOrderByOccurredAt(accountId).size shouldBe 0
        }

        then("the retention period (6 months by default) keeps it within and deletes it after") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms"), "FAST_ACCESS")

            retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(5).toInstant()) shouldBe 0
            retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(7).toInstant()) shouldBe 1
            signInLog.of(accountId).size shouldBe 0
        }
    }
})
