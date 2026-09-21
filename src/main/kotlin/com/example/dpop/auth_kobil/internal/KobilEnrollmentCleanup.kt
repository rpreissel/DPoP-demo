package com.example.dpop.auth_kobil.internal

import com.example.dpop.auth_kobil.KOBIL_ENROLLMENT_TYPE
import com.example.dpop.kobil_mock.KobilSsms
import com.example.dpop.kobil_mock.KobilUserRef
import com.example.dpop.tool_api.EnrollmentCleanup
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.stereotype.Component

/**
 * Revoking a KOBIL method has an outside half: the activation lives at the provider, so deleting
 * only our row would leave a device bound at KOBIL that nothing here still knows about. That
 * second call is a non-transactional external effect, the same class as the mock SMS send
 * (docs/07-betrieb.md #2) - our row goes either way.
 */
@Component
class KobilEnrollmentCleanup(
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val ssms: KobilSsms,
) : EnrollmentCleanup {
    override val enrollmentType = KOBIL_ENROLLMENT_TYPE

    override fun delete(enrollmentRef: EnrollmentRef) {
        val id = enrollmentRef.id.toLong()
        enrollmentRepository.findById(id).ifPresent { enrollment ->
            ssms.deprovisionUser(KobilUserRef(enrollment.kobilTenantId, enrollment.kobilUserId))
        }
        enrollmentRepository.deleteById(id)
    }
}
