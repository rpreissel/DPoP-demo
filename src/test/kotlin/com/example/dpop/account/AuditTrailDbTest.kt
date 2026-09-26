package com.example.dpop.account

import com.example.dpop.account.internal.AuditRetention
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-39: what an account deletion leaves behind is the audit trail without values - that and how,
 * never what - and only until the retention period since the deletion has passed.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditTrailDbTest(
    private val accountService: AccountService,
    private val auditRetention: AuditRetention,
    private val jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    beforeEach {
        jdbcTemplate.update("DELETE FROM account.account")
        jdbcTemplate.update("DELETE FROM account.audit_event")
    }

    fun events(accountId: Long): List<Map<String, Any?>> =
        jdbcTemplate.queryForList("SELECT event_type, subject, acr, source FROM account.audit_event WHERE account_id = ? ORDER BY id", accountId)

    fun livedThrough(accountId: Long) {
        accountService.addIdentification(accountId, "ident-fsc", "loa2", mapOf("ort" to "Musterstadt"))
        val method = accountService.addAuthenticationMethod(
            accountId, "sms", EnrollmentRef("auth_sms.enrollment", "1"), enrolledUnderAcr = "loa1", details = mapOf("phone" to "+491701234567")
        ).activeAuthenticationMethods.single()
        accountService.deactivateAuthenticationMethod(accountId, checkNotNull(method.id))
        accountService.deleteAccount(accountId)
    }

    given("an account that was identified, got a method, lost it and was deleted") {
        then("the trail survives the deletion - names and levels only, no value, while the value tables are gone") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            livedThrough(accountId)

            events(accountId).map { it["EVENT_TYPE"] } shouldBe listOf("IDENTIFIED", "METHOD_ADDED", "METHOD_DEACTIVATED", "ACCOUNT_DELETED")
            events(accountId)[0]["SUBJECT"] shouldBe "ident-fsc"
            events(accountId)[0]["ACR"] shouldBe "loa2"
            // No value of the account made it into the trail.
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account.audit_event WHERE account_id = ? AND (COALESCE(subject,'') LIKE '%Musterstadt%' OR COALESCE(source,'') LIKE '%+49%')",
                Int::class.java, accountId
            ) shouldBe 0
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.identification WHERE account_id = ?", Int::class.java, accountId) shouldBe 0
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.auth_method WHERE account_id = ?", Int::class.java, accountId) shouldBe 0
        }
    }

    given("the retention period (10 years by default)") {
        then("keeps the trail within it and deletes it once it has passed since the deletion") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            livedThrough(accountId)
            val nineYears = Instant.now().atZone(ZoneOffset.UTC).plusYears(9).toInstant()
            val elevenYears = Instant.now().atZone(ZoneOffset.UTC).plusYears(11).toInstant()

            auditRetention.purge(nineYears) shouldBe 0
            events(accountId).size shouldBe 4
            auditRetention.purge(elevenYears) shouldBe 4
            events(accountId).size shouldBe 0
        }

        then("never touches the trail of an account that still exists") {
            val accountId = accountService.createUnidentifiedAccount().accountId
            accountService.addIdentification(accountId, "ident-fsc", "loa2", null)
            auditRetention.purge(Instant.now().atZone(ZoneOffset.UTC).plusYears(50).toInstant()) shouldBe 0
            events(accountId).size shouldBe 1
        }
    }
})
