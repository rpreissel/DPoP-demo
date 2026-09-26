package com.example.dpop.orchestrator

import com.example.dpop.account.AccountService
import com.example.dpop.orchestrator.session.AccountDeletionService
import com.example.dpop.tool_spi.EnrollmentRef
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.support.GeneratedKeyHolder

/**
 * Review 2026-09, Phase F: a device key rebound to another account reuses its credential row (by
 * thumbprint). Deleting - or revoking on - the old account must leave that row alone while the new
 * account's method still points at it; it used to be deleted, and the new account ran into a 500.
 */
class SharedCredentialRowIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var accountService: AccountService

    @Autowired
    private lateinit var accountDeletionService: AccountDeletionService

    private fun deviceRow(): Long {
        val keys = GeneratedKeyHolder()
        jdbcTemplate.update({ connection ->
            connection.prepareStatement(
                "INSERT INTO auth_device.enrollment (kty, crv, x, y, thumbprint, created_at) VALUES ('EC', 'P-256', 'x', 'y', 'thumb-shared', CURRENT_TIMESTAMP)",
                arrayOf("id")
            )
        }, keys)
        return keys.key!!.toLong()
    }

    private fun rowExists(id: Long) =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_device.enrollment WHERE id = ?", Int::class.java, id) == 1

    init {
        given("two accounts whose device methods point at the same credential row") {
            then("deleting the first keeps the row; deleting the last one removes it") {
                val row = deviceRow()
                val ref = EnrollmentRef("auth_device.enrollment", row.toString())
                val first = accountService.createUnidentifiedAccount().accountId
                val second = accountService.createUnidentifiedAccount().accountId
                accountService.addAuthenticationMethod(first, "device", ref, enrolledUnderAcr = null, details = emptyMap(), allowsMultipleInstances = true)
                accountService.addAuthenticationMethod(second, "device", ref, enrolledUnderAcr = null, details = emptyMap(), allowsMultipleInstances = true)

                accountDeletionService.deleteAccount(first)
                rowExists(row) shouldBe true

                accountDeletionService.deleteAccount(second)
                rowExists(row) shouldBe false
            }
        }
    }
}
