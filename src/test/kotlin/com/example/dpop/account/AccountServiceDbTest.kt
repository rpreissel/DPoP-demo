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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.aopalliance.intercept.MethodInterceptor
import org.hibernate.exception.ConstraintViolationException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.aop.framework.Advised
import com.example.dpop.tool_spi.EnrollmentRef
import com.example.dpop.tool_spi.ToolId
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
    private val anchorRepository: AccountAnchorRepository
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
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
                }
            }
            accountService.allAccountIds() shouldBe listOf(holder.accountId)
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 1
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 1
            accountService.resolveByAnchor(AttributeType.PERSON_ID, "P000000555").shouldBeNull()
        }

        then("a failure after accepting every claim rolls back account, log, projection and anchors") {
            shouldThrow<IllegalStateException> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createUnidentifiedAccount()
                    accountService.recordClaims(subject.accountId, listOf(
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
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

    for ((type, value) in listOf(AttributeType.PERSON_ID to "P000000777", AttributeType.EMAIL to "shared@example.com")) {
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
                                    accountService.recordClaim(subject.accountId, Claim(type, value, ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
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
                        AttributeType.PERSON_ID -> winner.personId shouldBe value
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
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
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
            accountService.recordClaim(first.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)

            val second = accountService.createUnidentifiedAccount()
            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(second.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
            }

            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = 'P000000777'",
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

    // ADR-12: a retraction cancels a claim without touching the log, and established-claims
    // readers see assertions MINUS retractions. Needs the real schema - the `not exists`
    // subtraction is SQL.
    given("a claim that was retracted") {
        then("it stops counting although its log row stays") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(account.accountId, listOf(
                Claim(AttributeType.NAME, "Muster", ClaimSource.PERSON_DIRECTORY),
                Claim(AttributeType.VORNAME, "Max", ClaimSource.PERSON_DIRECTORY),
                Claim(AttributeType.GEBURTSDATUM, "1985-06-15", ClaimSource.PERSON_DIRECTORY)
            ), provenAcr = AcrLevel.LOA2)
            fun establishedValues() = accountService.establishedClaimValues(
                account.accountId, setOf(AttributeType.NAME, AttributeType.VORNAME, AttributeType.GEBURTSDATUM)
            )
            establishedValues() shouldBe mapOf(
                AttributeType.NAME to "Muster",
                AttributeType.VORNAME to "Max",
                AttributeType.GEBURTSDATUM to "1985-06-15"
            )

            jdbcTemplate.update(
                """INSERT INTO account.retraction (account_id, attribute_type, normalized_value, trust_anchor, retracted_at)
                   VALUES (?, 'name', 'muster', 'OPERATOR', CURRENT_TIMESTAMP)""",
                account.accountId
            )

            establishedValues() shouldBe mapOf(
                AttributeType.VORNAME to "Max",
                AttributeType.GEBURTSDATUM to "1985-06-15"
            )
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'name'",
                Int::class.java, account.accountId
            ) shouldBe 1
        }
    }

    given("a singleton method replaced by a new instance (review 2026-09, M-13)") {
        fun enroll(accountId: Long, method: String, claim: Claim, ref: String) {
            val instance = java.util.UUID.randomUUID()
            // Same order as the enrollment path: the new instance's claims first, then the instance.
            accountService.recordClaims(accountId, listOf(claim), provenAcr = AcrLevel.LOA1, authMethodId = instance)
            accountService.addAuthenticationMethod(accountId, method, EnrollmentRef("t", ref), "loa1", emptyMap(), instanceId = instance)
        }
        val smsTool = ClaimSource.of(ToolId("enroll-sms"))
        val passwordTool = ClaimSource.of(ToolId("enroll-password"))

        then("the old phone number stops counting, the new one counts") {
            val account = accountService.createUnidentifiedAccount()
            enroll(account.accountId, "sms", Claim(AttributeType.PHONE_NUMBER, "+491700000001", smsTool), "s-1")
            enroll(account.accountId, "sms", Claim(AttributeType.PHONE_NUMBER, "+491700000002", smsTool), "s-2")

            accountService.establishedClaimValues(account.accountId, setOf(AttributeType.PHONE_NUMBER)) shouldBe
                mapOf(AttributeType.PHONE_NUMBER to "+491700000002")
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.retraction WHERE account_id = ? AND attribute_type = 'phone_number'",
                Int::class.java, account.accountId
            ) shouldBe 1
        }

        then("a value the replacement asserts itself stays - a new password still means 'has a password'") {
            val account = accountService.createUnidentifiedAccount()
            val exists = Claim(AttributeType.PASSWORD_EXISTS, com.example.dpop.tool_spi.PASSWORD_EXISTS_MARKER, passwordTool)
            enroll(account.accountId, "password", exists, "p-1")
            enroll(account.accountId, "password", exists, "p-2")

            accountService.findAccount(account.accountId)!!.establishedClaims.keys.contains(AttributeType.PASSWORD_EXISTS) shouldBe true
        }
    }

    given("the Personenverzeichnis moves a Versicherungsnummer to another person before the old holder's own change arrived") {
        then("it is released from the stale holder instead of failing on the anchor conflict forever (review 2026-09, M-13)") {
            fun bound(personId: String, versnr: String): Long {
                val account = accountService.createUnidentifiedAccount()
                accountService.recordClaims(
                    account.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.VERSNR, versnr, ClaimSource.PERSON_DIRECTORY)
                    ),
                    provenAcr = AcrLevel.LOA2
                )
                return account.accountId
            }
            fun versnrOf(accountId: Long): String? = jdbcTemplate.queryForList(
                "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'versnr'",
                String::class.java, accountId
            ).firstOrNull()
            val stale = bound("P000000001", "10000001")
            val receiver = bound("P000000002", "10000002")

            accountService.applyDirectoryChange(
                com.example.dpop.tool_api.PersonChanged("P000000002", setOf(AttributeType.VERSNR), kvnr = null, versnr = "10000001")
            )

            versnrOf(receiver) shouldBe "10000001"
            versnrOf(stale).shouldBeNull()
            jdbcTemplate.queryForObject(
                "SELECT trust_anchor FROM account.retraction WHERE account_id = ? AND attribute_type = 'versnr'",
                String::class.java, stale
            ) shouldBe "PERSON_DIRECTORY"
        }
    }

    given("an identity anchor and a withdrawal in the holder's name") {
        then("the account module itself refuses it, whatever the caller checked (review 2026-09, S-7)") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(
                account.accountId,
                listOf(Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY)),
                provenAcr = AcrLevel.LOA2
            )

            shouldThrow<IllegalStateException> {
                accountService.retractAttribute(account.accountId, AttributeType.PERSON_ID, RetractionAnchor.ACCOUNT_HOLDER)
            }
            accountService.findAccount(account.accountId)!!.personId shouldBe "P000000042"
        }
    }

    given("an eid restricted_id anchor being replaced by a new card (ADR-19)") {
        then("the replace commits in place, like EMAIL - the account keeps exactly one") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )

            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                Int::class.java, account.accountId
            ) shouldBe 1
            jdbcTemplate.queryForObject(
                "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                String::class.java, account.accountId
            ) shouldBe "T0909090Z9X8Y7W6V5U4T3S2R1"
        }
    }

    given("an eid restricted_id another account already holds") {
        then("the cross-account write refuses - a card pseudonym is never re-pointed to a second account") {
            val first = accountService.createUnidentifiedAccount()
            val second = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                first.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )

            shouldThrow<IdentityConflictException> {
                accountService.recordClaim(
                    second.accountId,
                    Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                    provenAcr = AcrLevel.LOA2
                )
            }
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
                    Claim(AttributeType.PERSON_ID, "P000004711", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
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
                "SELECT established_acr FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                String::class.java, account.accountId
            ) shouldBe "loa1"
        }
    }

    // ADR-19 / ADR-12-Nachtrag: the claim log is a change log, not a run log. ADR-18's eid runs
    // cost 8 rows each time; re-attesting an unchanged card must cost nothing.
    given("a claim log that already established a tool's values") {
        then("re-attesting the same card logs nothing new (change log, not run log)") {
            val account = accountService.createUnidentifiedAccount()
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val card = listOf(
                Claim(AttributeType.NAME, "Mustermann", eid, AcrLevel.LOA3),
                Claim(AttributeType.VORNAME, "Max", eid, AcrLevel.LOA3),
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            )
            repeat(2) { accountService.recordClaims(account.accountId, card, provenAcr = AcrLevel.LOA2) }

            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, account.accountId
            ) shouldBe 3
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, account.accountId
            ) shouldBe 1
        }
    }

    // ADR-20: the provisional account the journey created for an attestation yields to the
    // account the correlation step resolves. Needs the real schema - the whole point is that
    // `ux_anchor_value` is global, so the yielding account's anchors must be gone before the
    // very same values are written on the absorbing one.
    given("a provisional account whose attestation resolves to another account") {
        then("it yields: anchors, claims and the identification audit move, the account is gone") {
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val provisional = accountService.createUnidentifiedAccount()
            accountService.recordClaims(provisional.accountId, listOf(
                Claim(AttributeType.NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.VORNAME, "Max", eid, AcrLevel.LOA3),
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            ), provenAcr = AcrLevel.LOA2)
            accountService.addIdentification(provisional.accountId, "eid", "loa3", mapOf("provider" to "eid-mock-service"))
            val target = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                target.accountId, Claim(AttributeType.PERSON_ID, "P000000001", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2
            )

            accountService.absorbProvisionalAccount(provisional.accountId, target.accountId)

            accountService.findAccount(provisional.accountId).shouldBeNull()
            accountService.findAccount(target.accountId)!!.personId shouldBe "P000000001"
            anchorRepository.findByAccountIdAndAttributeType(target.accountId, AttributeType.EID_RESTRICTED_ID)!!.value shouldBe
                "T0103005K1D5S0V8T9W6UM2RTX"
            accountService.establishedClaimValues(target.accountId, setOf(AttributeType.NAME, AttributeType.VORNAME)) shouldBe
                mapOf(AttributeType.NAME to "Muster", AttributeType.VORNAME to "Max")
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.identification WHERE account_id = ? AND method = 'eid'",
                Int::class.java, target.accountId
            ) shouldBe 1
            jdbcTemplate.queryForObject(
                "SELECT details FROM account.identification WHERE account_id = ? AND method = 'eid'",
                String::class.java, target.accountId
            )!! shouldContain "absorbedFromAccountId"
        }

        then("an anchor the absorbing account already holds with the same value is a no-op, not a conflict") {
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val provisional = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                provisional.accountId, Claim(AttributeType.EMAIL, "max@example.com", eid), provenAcr = AcrLevel.LOA2
            )
            val target = accountService.createUnidentifiedAccount()

            accountService.absorbProvisionalAccount(provisional.accountId, target.accountId)

            accountService.findAccount(target.accountId)!!.email shouldBe "max@example.com"
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'email' AND normalized_value = 'max@example.com'",
                Int::class.java
            ) shouldBe 1
        }
    }

    // The mirror image, which "Enrollment zuerst" runs into: the durable account is the one in
    // hand, and the placeholder an abandoned eID run left behind is what resolution finds. Same
    // port, arguments the other way round - the provisional account yields either way.
    given("a provisional leftover that an already-enrolled account identifies into") {
        then("the leftover is absorbed into the account that holds the credentials") {
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val leftover = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                leftover.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0304223A9B1N7K5D2PN1S44QE", eid, AcrLevel.LOA3),
                provenAcr = AcrLevel.LOA2
            )
            val enrolled = accountService.createUnidentifiedAccount()
            accountService.addAuthenticationMethod(
                enrolled.accountId, "password", EnrollmentRef("auth_password", "e-3"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )

            accountService.absorbProvisionalAccount(leftover.accountId, enrolled.accountId)

            accountService.findAccount(leftover.accountId).shouldBeNull()
            anchorRepository.findByAccountIdAndAttributeType(enrolled.accountId, AttributeType.EID_RESTRICTED_ID)!!.value shouldBe
                "T0304223A9B1N7K5D2PN1S44QE"
            accountService.findAccount(enrolled.accountId)!!.activeAuthenticationMethods.size shouldBe 1
        }
    }

    given("an account that is not provisional") {
        then("it never yields - a credential was enrolled on it, so this would be an account merge") {
            val notProvisional = accountService.createUnidentifiedAccount()
            accountService.addAuthenticationMethod(
                notProvisional.accountId, "password", EnrollmentRef("auth_password", "e-1"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )
            val target = accountService.createUnidentifiedAccount()

            accountService.findAccount(notProvisional.accountId)!!.isProvisional shouldBe false
            shouldThrow<IdentityConflictException> {
                accountService.absorbProvisionalAccount(notProvisional.accountId, target.accountId)
            }
            accountService.findAccount(notProvisional.accountId) shouldNotBe null
        }

        then("a deactivated credential still counts - its claims\' provenance points at this account") {
            val account = accountService.createUnidentifiedAccount()
            val profile = accountService.addAuthenticationMethod(
                account.accountId, "password", EnrollmentRef("auth_password", "e-2"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )
            accountService.deactivateAuthenticationMethod(account.accountId, profile.authenticationMethods.first().id!!)

            val reread = accountService.findAccount(account.accountId)!!
            reread.activeAuthenticationMethods.shouldBeEmpty()
            reread.isProvisional shouldBe false
        }
    }

    given("an anchor that a replacement claim re-points") {
        then("the old value is retracted so the log agrees with the anchor (ADR-19)") {
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA1
            )
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2
            )

            accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EMAIL)) shouldBe
                mapOf(AttributeType.EMAIL to "new@example.com")
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.retraction WHERE account_id = ?", Int::class.java, account.accountId
            ) shouldBe 1
        }

        then("a re-proven value counts again - the cycle a -> b -> a ends established on a") {
            val account = accountService.createUnidentifiedAccount()
            for (value in listOf("a@example.com", "b@example.com", "a@example.com")) {
                accountService.recordClaim(
                    account.accountId, Claim(AttributeType.EMAIL, value, ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2
                )
            }

            accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EMAIL)) shouldBe
                mapOf(AttributeType.EMAIL to "a@example.com")
            accountService.findAccountByEmail("a@example.com")?.accountId shouldBe account.accountId
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'email'",
                Int::class.java, account.accountId
            ) shouldBe 3
        }
    }

    given("a card anchor replaced by a value that differs only in case (review 2026-09, Phase F)") {
        then("the new claim stands - the replacement does not void what it just set") {
            val account = accountService.createUnidentifiedAccount()
            val source = ClaimSource.of(com.example.dpop.tool_spi.ToolId("ident-eid"))
            accountService.recordClaim(account.accountId, Claim(AttributeType.EID_RESTRICTED_ID, "AbC123", source), provenAcr = AcrLevel.LOA3)
            accountService.recordClaim(account.accountId, Claim(AttributeType.EID_RESTRICTED_ID, "ABC123", source), provenAcr = AcrLevel.LOA3)

            accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EID_RESTRICTED_ID))[AttributeType.EID_RESTRICTED_ID] shouldBe "ABC123"
        }
    }
})
