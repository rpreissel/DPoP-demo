package com.example.dpop.account

import com.example.dpop.account.internal.AccountAnchorRepository
import com.example.dpop.orchestrator.api.v1.OrchestratorExceptionHandler
import com.example.dpop.tool_api.IdentityConflictException
import com.example.dpop.tool_spi.AcrLevel
import com.example.dpop.tool_spi.AttributeType
import com.example.dpop.tool_spi.Claim
import com.example.dpop.tool_spi.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.aopalliance.intercept.MethodInterceptor
import org.hibernate.exception.ConstraintViolationException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.aop.framework.Advised
import com.example.dpop.account.RetractionAnchor
import com.example.dpop.tool_spi.ToolId
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    private val anchorRepository: AccountAnchorRepository,
    private val claimRepository: com.example.dpop.account.internal.AccountClaimRepository
) : BehaviorSpec({

    beforeEach {
        listOf("account.account").forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    given("account creation and claim adoption sharing the caller transaction") {
        then("a later claim conflict also removes the newly created account") {
            val holder = accountService.createUnidentifiedAccount()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
            shouldThrow<IdentityConflictException> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createUnidentifiedAccount()
                    accountService.recordClaims(subject.accountId, listOf(
                        Claim(AttributeType.PERSON_ID, "555", ClaimSource.EXT_STAMMDATEN),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
                }
            }
            accountService.allAccountIds() shouldBe listOf(holder.accountId)
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 1
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 1
            accountService.resolveByAnchor(AttributeType.PERSON_ID, "555").shouldBeNull()
        }

        then("a failure after accepting every claim rolls back account, log, projection and anchors") {
            shouldThrow<IllegalStateException> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createUnidentifiedAccount()
                    accountService.recordClaims(subject.accountId, listOf(
                        Claim(AttributeType.PERSON_ID, "555", ClaimSource.EXT_STAMMDATEN),
                        Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
                    error("Later journey step failed")
                }
            }
            accountService.allAccountIds() shouldBe emptyList()
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 0
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 0
        }
    }

    for ((type, value) in listOf(AttributeType.PERSON_ID to "777", AttributeType.EMAIL to "shared@example.com")) {
        given("two concurrent new accounts claiming the same ${type.wireName}") {
            then("one commits and the unique-conflict loser leaves no account or claims behind") {
                val barrier = CyclicBarrier(2)
                val repositoryProxy = checkNotNull(anchorRepository as? Advised)
                val synchronizeInsert = MethodInterceptor { invocation ->
                    // Both transactions pass the real ownership queries before either inserts.
                    if (invocation.method.name == "save") barrier.await(10, TimeUnit.SECONDS)
                    invocation.proceed()
                }
                repositoryProxy.addAdvice(0, synchronizeInsert)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val attempts = (1..2).map {
                        executor.submit<Result<Long>> {
                            runCatching {
                                checkNotNull(TransactionTemplate(transactionManager).execute {
                                    accountService.resolveByAnchor(type, value).shouldBeNull()
                                    val subject = accountService.createUnidentifiedAccount()
                                    accountService.recordClaim(subject.accountId, Claim(type, value, ClaimSource.EXT_STAMMDATEN), provenAcr = AcrLevel.LOA2)
                                    subject.accountId
                                })
                            }
                        }
                    }
                    val results = attempts.map { it.get(20, TimeUnit.SECONDS) }
                    results.count { it.isSuccess } shouldBe 1
                    val winnerId = results.single { it.isSuccess }.getOrThrow()
                    val failure = checkNotNull(results.single { it.isFailure }.exceptionOrNull())
                    val violation = generateSequence(failure) { it.cause }
                        .filterIsInstance<ConstraintViolationException>().first()
                    OrchestratorExceptionHandler().handleConstraintViolation(violation).statusCode shouldBe HttpStatus.CONFLICT
                    accountService.allAccountIds() shouldBe listOf(winnerId)
                    accountService.resolveByAnchor(type, value) shouldBe winnerId
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 1
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 1
                    val winner = checkNotNull(accountService.findAccount(winnerId))
                    when (type) {
                        AttributeType.PERSON_ID -> winner.personId shouldBe value.toLong()
                        AttributeType.EMAIL -> winner.email shouldBe value
                        else -> error("Unexpected test attribute")
                    }
                } finally {
                    executor.shutdownNow()
                    check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent claim tasks did not terminate" }
                    repositoryProxy.removeAdvice(synchronizeInsert)
                }
            }
        }
    }

    given("a claim batch whose second claim conflicts with another account's anchor") {
        then("the whole batch rolls back - no partial log/projection/anchor survives from the first claim") {
            val holder = accountService.createUnidentifiedAccount()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)

            val subject = accountService.createUnidentifiedAccount()

            shouldThrow<IdentityConflictException> {
                accountService.recordClaims(
                    subject.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, "555", ClaimSource.EXT_STAMMDATEN),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
            }

            // recordClaims is one @Transactional method - the EMAIL claim's failure must undo
            // the PERSON_ID claim already processed earlier in the SAME call, not just stop
            // applying further ones.
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, subject.accountId
            ) shouldBe 0
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, subject.accountId
            ) shouldBe 0
            accountService.findAccount(subject.accountId)?.personId.shouldBeNull()
        }
    }

    given("two existing accounts rebinding email concurrently") {
        then("a unique conflict at commit rolls back the losing projection and claim") {
            val ids = (1..2).map { index ->
                val account = accountService.createUnidentifiedAccount()
                accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old$index@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
                account.accountId
            }
            val commitBarrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results = ids.map { accountId ->
                    executor.submit<Result<Long>> {
                        runCatching {
                            checkNotNull(TransactionTemplate(transactionManager).execute {
                                accountService.recordClaim(accountId, Claim(AttributeType.EMAIL, "shared@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
                                // Existing anchor updates are deferred: both callbacks finish before commit.
                                commitBarrier.await(10, TimeUnit.SECONDS)
                                accountId
                            })
                        }
                    }
                }.map { it.get(20, TimeUnit.SECONDS) }
                results.count { it.isSuccess } shouldBe 1
                val winner = results.single { it.isSuccess }.getOrThrow()
                val loser = ids.single { it != winner }
                val failure = checkNotNull(results.single { it.isFailure }.exceptionOrNull())
                val violation = generateSequence(failure) { it.cause }.filterIsInstance<ConstraintViolationException>().first()
                OrchestratorExceptionHandler().handleConstraintViolation(violation).statusCode shouldBe HttpStatus.CONFLICT
                accountService.resolveByAnchor(AttributeType.EMAIL, "shared@example.com") shouldBe winner
                accountService.findAccount(loser)?.email shouldBe "old${ids.indexOf(loser) + 1}@example.com"
                accountService.allAccountIds().sorted() shouldBe ids.sorted()
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 3
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 2
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent rebind tasks did not terminate" }
            }
        }
    }

    given("two accounts, the second trying to claim a person_id the first already holds") {
        then("the real unique index rejects it - exactly one owner survives") {
            val first = accountService.createUnidentifiedAccount()
            accountService.recordClaim(first.accountId, Claim(AttributeType.PERSON_ID, "777", ClaimSource.EXT_STAMMDATEN), provenAcr = AcrLevel.LOA2)

            val second = accountService.createUnidentifiedAccount()
            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(second.accountId, Claim(AttributeType.PERSON_ID, "777", ClaimSource.EXT_STAMMDATEN), provenAcr = AcrLevel.LOA2)
            }

            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = '777'",
                Int::class.java
            ) shouldBe 1
            accountService.findAccount(second.accountId)?.personId.shouldBeNull()
        }
    }

    given("an account's email anchor being rebound to a new value") {
        then("the rebind commits atomically under real unique constraints") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)

            accountService.findAccountByEmail("old@example.com").shouldBeNull()
            accountService.findAccountByEmail("new@example.com")?.accountId shouldBe account.accountId
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                Int::class.java, account.accountId
            ) shouldBe 1
        }
    }

    // ADR-12: a retraction cancels a claim without touching the log, and the matching layer reads
    // assertions MINUS retractions. Needs the real schema - the `not exists` subtraction is SQL.
    given("a claim that was retracted") {
        then("it stops matching although its log row stays") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(account.accountId, listOf(
                Claim(AttributeType.NAME, "Muster", ClaimSource.EXT_STAMMDATEN),
                Claim(AttributeType.VORNAME, "Max", ClaimSource.EXT_STAMMDATEN),
                Claim(AttributeType.GEBURTSDATUM, "1985-06-15", ClaimSource.EXT_STAMMDATEN)
            ), provenAcr = AcrLevel.LOA2)
            fun matches() = claimRepository.findAccountIdsMatchingAllThree(
                AttributeType.NAME, "muster",
                AttributeType.VORNAME, "max",
                AttributeType.GEBURTSDATUM, "1985-06-15",
                PageRequest.of(0, 51)
            )
            matches() shouldBe listOf(account.accountId)

            jdbcTemplate.update(
                """INSERT INTO account.retraction (account_id, attribute_type, normalized_value, trust_anchor, retracted_at)
                   VALUES (?, 'name', 'muster', 'OPERATOR', CURRENT_TIMESTAMP)""",
                account.accountId
            )

            matches().shouldBeEmpty()
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'name'",
                Int::class.java, account.accountId
            ) shouldBe 1
        }
    }

    given("a method instance that asserted a module-owned value and an account-owned one") {
        then("revoking it retracts only what the module owned") {
            val account = accountService.createUnidentifiedAccount()
            val instanceId = java.util.UUID.randomUUID()
            accountService.recordClaims(
                account.accountId,
                listOf(
                    Claim(AttributeType.PHONE_NUMBER, "+491701234567", ClaimSource.of(ToolId("enroll-sms"))),
                    Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ToolId("enroll-sms")))
                ),
                authMethodId = instanceId,
                provenAcr = AcrLevel.LOA2
            )

            accountService.retractClaimsOf(
                account.accountId, instanceId.toString(), RetractionAnchor.ACCOUNT_MANAGEMENT
            ) shouldBe 1

            // The phone number was the module's; the email is the account's own identity anchor and
            // survives - otherwise removing the sms method would take password login with it.
            jdbcTemplate.queryForObject(
                "SELECT attribute_type FROM account.retraction WHERE account_id = ?",
                String::class.java, account.accountId
            ) shouldBe "phone_number"
            accountService.findAccount(account.accountId)?.email shouldBe "max@example.com"
        }
    }

    // ADR-5's line applied to anchors: a write is priced by what the session actually proved,
    // not by what the asserting tool declares for itself.
    given("an anchor write below its declared floor") {
        then("establishing a person_id at loa1 is refused, and nothing is anchored") {
            val account = accountService.createUnidentifiedAccount()

            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(
                    account.accountId,
                    Claim(AttributeType.PERSON_ID, "4711", ClaimSource.EXT_STAMMDATEN, AcrLevel.LOA2),
                    provenAcr = AcrLevel.LOA1
                )
            }
            accountService.findAccount(account.accountId)?.personId.shouldBeNull()
        }

        then("replacing an email costs loa2 even though establishing it cost loa1") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EMAIL, "first@example.com", ClaimSource.SELF_REPORTED),
                provenAcr = AcrLevel.LOA1
            )

            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(
                    account.accountId,
                    Claim(AttributeType.EMAIL, "second@example.com", ClaimSource.SELF_REPORTED),
                    provenAcr = AcrLevel.LOA1
                )
            }
            accountService.findAccount(account.accountId)?.email shouldBe "first@example.com"
        }
    }

    given("an anchor that was written") {
        then("it remembers the level the session actually proved, not the claim's own") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                // The claim declares loa2; the session only ever proved loa1, and that is what counts.
                Claim(AttributeType.EMAIL, "capped@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA2),
                provenAcr = AcrLevel.LOA1
            )

            jdbcTemplate.queryForObject(
                "SELECT established_loa FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                String::class.java, account.accountId
            ) shouldBe "loa1"
        }
    }
})
