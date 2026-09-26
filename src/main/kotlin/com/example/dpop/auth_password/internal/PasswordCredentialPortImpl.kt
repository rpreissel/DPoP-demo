package com.example.dpop.auth_password.internal

import com.example.dpop.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_spi.EnrollmentRef
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implements [PasswordCredentialPort] for callers outside a Channel/ToolSession (Keycloak's native
 * password credential) - reuses exactly the same hashing/storage as [AuthPasswordToolHandler]/
 * [EnrollPasswordToolHandler][com.example.dpop.auth_password.internal.enrollpassword.EnrollPasswordToolHandler],
 * just without the ToolSession indirection.
 */
@Component
internal class PasswordCredentialPortImpl(
    private val enrollmentRepository: AuthPasswordEnrollmentRepository
) : PasswordCredentialPort {

    @Transactional
    override fun verify(enrollmentRef: EnrollmentRef?, candidate: String): Boolean {
        val enrollment = enrollmentRef?.id?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
        // Unconditional, constant-cost check regardless of whether an enrollment was found - see
        // PasswordHasher.matches's KDoc on why a null-guarded short-circuit would reopen a
        // timing-based account-enumeration oracle.
        val matches = PasswordHasher.matches(candidate, enrollment?.passwordHash)
        if (matches && enrollment != null) PasswordHasher.upgrade(enrollment, candidate)
        return matches
    }

    @Transactional
    override fun setNew(password: String): EnrollmentRef {
        PasswordPolicy.check(password)?.let(PasswordPolicy::reject)
        val enrollment = enrollmentRepository.save(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(password)))
        return EnrollmentRef(type = PASSWORD_ENROLLMENT_TYPE, id = enrollment.id.toString())
    }
}
