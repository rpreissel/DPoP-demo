package com.example.dpop.account.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * Append-only audit record of one act of establishing identity: which procedure ([method]), at
 * which level, when, and the proof anchors it produced ([details]: role, provider, provider
 * transaction id, method version, evidence hash, channel, journey - "dass und wie", never "was",
 * docs/06-ablaeufe.md #1). Complements [AccountClaim]: a claim names its source register (e.g.
 * `ext_stammdaten`), not the procedure that checked it. Never read for a decision - `AuthEvidence`
 * is re-proven per session.
 *
 * One run can leave TWO rows, because ADR-18 splits an identification into two acts: attesting who
 * somebody is (`ident-eid`) and binding that person to the register (`ident-kvnr`). Both are
 * recorded - the binding especially, since it is the moment the PERSON_ID anchor came to exist.
 * `details.role` says which act a row was ([com.example.dpop.tool_spi.MethodRole]), so a
 * correlation row is not mistaken for a procedure that reached its level on its own; rows of the
 * same run share their `journeyId`.
 */
@Entity
@Table(schema = "account", name = "identification")
class AccountIdentification(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "method", nullable = false)
    var method: String? = null,

    @Column(name = "achieved_loa")
    var achievedLoa: String? = null,

    @Column(name = "identified_at", nullable = false)
    var identifiedAt: Instant? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    var details: Map<String, Any?>? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
}
