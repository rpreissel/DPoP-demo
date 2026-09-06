package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implements [PasswordCredentialPort] for callers outside a Channel/ToolSession (Keycloak's native
 * password credential) - reuses exactly the same hashing/storage as [AuthPasswordUseToolHandler]/
 * [EnrollPasswordToolHandler][com.example.dpop.auth_password.internal.enrollpassword.EnrollPasswordToolHandler],
 * just without the ToolSession indirection.
 */
@Component
internal class PasswordCredentialPortImpl(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) : PasswordCredentialPort {

    override fun verify(enrollmentRef: EnrollmentRef?, candidate: String): Boolean {
        val enrollment = enrollmentRef?.id?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
        // Unconditional, constant-cost check regardless of whether an enrollment was found - see
        // PasswordHasher.matches's KDoc on why a null-guarded short-circuit would reopen a
        // timing-based account-enumeration oracle.
        return PasswordHasher.matches(candidate, enrollment?.passwordHash)
    }

    @Transactional
    override fun setNew(password: String): EnrollmentRef {
        val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(password)))
        return EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString())
    }
}
