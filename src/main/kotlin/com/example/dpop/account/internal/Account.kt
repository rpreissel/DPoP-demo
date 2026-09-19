package com.example.dpop.account.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * Identity key and optimistic-lock root of an account - nothing else. Current state lives in rows
 * keyed by it ([AccountAnchor], [AccountAuthMethod]); history in append-only logs
 * ([AccountClaim], [AccountIdentification]).
 *
 * [version] serializes changes to the CURRENT state: `AccountService` loads the row with
 * `OPTIMISTIC_FORCE_INCREMENT` before touching anchors or methods, so two concurrent writers for
 * the same account cannot both succeed (the loser gets `409 CONCURRENT_MODIFICATION`). Log
 * appends never bump it.
 */
@Entity
@Table(schema = "account", name = "account")
class Account(
    var createdAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Version
    var version: Long? = null
}
