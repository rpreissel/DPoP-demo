package com.example.dpop.account

import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * Real-DB counterpart to AccountServiceTest's mocked unit coverage (docs/ideen/account-
 * attribute-und-trust-vereinheitlichen.md, Paket 7, Abnahmekriterium "Echte DB-Transaktionen"):
 * verifies actual Spring @Transactional rollback and real unique-constraint enforcement,
 * neither of which a MockK-based test can demonstrate - [AccountServiceTest] pins the per-call
 * decisions (what gets rejected, what gets written), this file pins that the surrounding
 * transaction genuinely commits or rolls back as one unit against the real H2 schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceDbTest(
    private val accountService: AccountService,
    private val jdbcTemplate: JdbcTemplate
) : BehaviorSpec({

    beforeEach {
        listOf("account_anchor", "account_attribute", "account").forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    given("a claim batch whose second claim conflicts with another account's anchor") {
        then("the whole batch rolls back - no partial log/projection/anchor survives from the first claim") {
            val holder = accountService.createUnidentifiedAccount()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED))

            val subject = accountService.createUnidentifiedAccount()

            shouldThrow<IdentityConflictException> {
                accountService.recordClaims(
                    subject.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, "555", ClaimSource.EXT_STAMMDATEN),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    )
                )
            }

            // recordClaims is one @Transactional method - the EMAIL claim's failure must undo
            // the PERSON_ID claim already processed earlier in the SAME call, not just stop
            // applying further ones.
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_attribute WHERE account_id = ?", Int::class.java, subject.accountId
            ) shouldBe 0
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_anchor WHERE account_id = ?", Int::class.java, subject.accountId
            ) shouldBe 0
            accountService.findAccount(subject.accountId)?.personId.shouldBeNull()
        }
    }

    given("two accounts, the second trying to claim a person_id the first already holds") {
        then("the real unique index rejects it - exactly one owner survives") {
            val first = accountService.createUnidentifiedAccount()
            accountService.recordClaim(first.accountId, Claim(AttributeType.PERSON_ID, "777", ClaimSource.EXT_STAMMDATEN))

            val second = accountService.createUnidentifiedAccount()
            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(second.accountId, Claim(AttributeType.PERSON_ID, "777", ClaimSource.EXT_STAMMDATEN))
            }

            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_anchor WHERE anchor_type = 'person_id' AND anchor_value = '777'",
                Int::class.java
            ) shouldBe 1
            accountService.findAccount(second.accountId)?.personId.shouldBeNull()
        }
    }

    given("an account's email anchor being rebound to a new value") {
        then("the rebind commits atomically under real unique constraints") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED))
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED))

            accountService.findAccountByEmail("old@example.com").shouldBeNull()
            accountService.findAccountByEmail("new@example.com")?.accountId shouldBe account.accountId
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_anchor WHERE account_id = ? AND anchor_type = 'email'",
                Int::class.java, account.accountId
            ) shouldBe 1
        }
    }
})
