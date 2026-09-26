package com.example.dpop.account

import com.example.dpop.account.internal.ChangeLogEntry
import com.example.dpop.account.internal.ChangeLogRepository
import com.example.dpop.account.internal.ChangeLogRetention
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-39: what an account deletion leaves behind is the change log without values - that and how,
 * never what - and only until the retention period since the deletion has passed.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChangeLogDbTest(
    private val accountService: AccountService,
    private val changeLogRetention: ChangeLogRetention,
    private val changeLogRepository: ChangeLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    beforeEach {
        jdbcTemplate.update("DELETE FROM account.account")
        jdbcTemplate.update("DELETE FROM account.change_log")
    }

    fun events(accountId: Long): List<ChangeLogEntry> = changeLogRepository.findByAccountIdOrderByOccurredAt(accountId)

    fun livedThrough(accountId: Long) {
        accountService.addIdentification(accountId, "ident-fsc", "loa2", role = "IDENTIFICATION",
            report = mapOf("provider" to "fsc-service", "providerTxId" to "FSC-1", "documentNumber" to "C01X00T47"))
        val method = accountService.addAuthenticationMethod(
            accountId, "sms", EnrollmentRef("auth_sms.enrollment", "1"), enrolledUnderAcr = "loa1", details = mapOf("phone" to "+491701234567"),
            enrolledUnderAmr = listOf("email", "password"), channel = "WEB"
        ).activeAuthenticationMethods.single()
        accountService.deactivateAuthenticationMethod(accountId, checkNotNull(method.id))
        accountService.deleteAccount(accountId)
    }

    given("an account that was identified, got a method, lost it and was deleted") {
        then("the trail survives the deletion - names and levels only, no value, while the value tables are gone") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            livedThrough(accountId)

            val trail = events(accountId)
            trail.map { it.changeType.name } shouldBe listOf("IDENTIFIED", "METHOD_ADDED", "METHOD_DEACTIVATED", "ACCOUNT_DELETED")
            trail[0].subject shouldBe "ident-fsc"
            trail[0].acr shouldBe "loa2"
            // Every row names its own type and version, so it explains itself without this code.
            trail.forEach { it.details!!["type"] shouldBe it.changeType.name; it.details!!["version"] shouldBe 1 }
            // The references are kept, the document number the tool reported is not (§ 20 PAuswG).
            trail[0].details!!["providerTxId"] shouldBe "FSC-1"
            trail[0].details!!.containsKey("documentNumber") shouldBe false
            // How the method was added outlives it: the session's proofs and the channel.
            trail[1].details!!["amr"] shouldBe listOf("email", "password")
            trail[1].details!!["channel"] shouldBe "WEB"
            trail[2].details!!["reason"] shouldBe "REMOVED_BY_HOLDER"
            // No value of the account made it into the trail.
            trail.none { it.details.toString().contains("+49") } shouldBe true
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.auth_method WHERE account_id = ?", Int::class.java, accountId) shouldBe 0
        }
    }

    given("the retention period (10 years by default)") {
        then("keeps the trail within it and deletes it once it has passed since the deletion") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            livedThrough(accountId)
            val nineYears = Instant.now().atZone(ZoneOffset.UTC).plusYears(9).toInstant()
            val elevenYears = Instant.now().atZone(ZoneOffset.UTC).plusYears(11).toInstant()

            changeLogRetention.purge(nineYears) shouldBe 0
            events(accountId).size shouldBe 4
            changeLogRetention.purge(elevenYears) shouldBe 4
            events(accountId).size shouldBe 0
        }

        then("never touches the trail of an account that still exists") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            accountService.addIdentification(accountId, "ident-fsc", "loa2", null)
            changeLogRetention.purge(Instant.now().atZone(ZoneOffset.UTC).plusYears(50).toInstant()) shouldBe 0
            events(accountId).size shouldBe 1
        }
    }
})
