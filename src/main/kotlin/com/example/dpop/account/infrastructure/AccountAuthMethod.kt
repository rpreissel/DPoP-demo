package com.example.dpop.account.infrastructure

import com.example.dpop.tool_spi.EnrollmentRef
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * One registered authentication method instance of an account (docs/06-ablaeufe.md #1). The
 * credential itself belongs to the method module; [enrollmentType]/[enrollmentId] are the
 * [EnrollmentRef] to it, and this row is the only place that link exists. Deactivated rows stay,
 * so account deletion still reaches every credential ever referenced.
 *
 * [enrolledUnderAcr] caps what the method can ever authenticate to (ADR-5). [details] is opaque,
 * method-specific data this module never interprets - only what the owning module reads back
 * itself (`auth_device`'s binding key, KOBIL's device id). No audit evidence: it goes with the
 * method, while how the method was added is recorded in the change log (ADR-39).
 */
@Entity
@Table(schema = "account", name = "auth_method")
class AccountAuthMethod(
    @Column(name = "account_id", nullable = false)
    var accountId: Long? = null,

    @Column(name = "method", nullable = false)
    var method: String? = null,

    @Column(name = "enrollment_type", nullable = false)
    var enrollmentType: String? = null,

    @Column(name = "enrollment_id", nullable = false)
    var enrollmentId: String? = null,

    @Column(name = "enrolled_under_acr")
    var enrolledUnderAcr: String? = null,

    @Column(name = "label")
    var label: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    var details: Map<String, Any?>? = null
) {
    /** Stable instance id - the only thing `DELETE .../methods/{id}` addresses, since a method name may have several active instances. */
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID? = UUID.randomUUID()

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = Instant.now()

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null

    val enrollmentRef: EnrollmentRef
        get() = EnrollmentRef(checkNotNull(enrollmentType), checkNotNull(enrollmentId))

    fun deactivate(at: Instant) {
        active = false
        deactivatedAt = at
    }
}
