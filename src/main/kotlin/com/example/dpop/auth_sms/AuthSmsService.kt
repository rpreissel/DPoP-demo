package com.example.dpop.auth_sms

import com.example.dpop.auth_sms.internal.AuthSmsSetup
import com.example.dpop.auth_sms.internal.AuthSmsSetupRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant

@Service
class AuthSmsService(private val repository: AuthSmsSetupRepository) {

    @Transactional
    fun startEnrollment(phoneNumber: String): AuthSmsEnrollResult {
        require(phoneNumber.isNotBlank()) { "Telefonnummer ist erforderlich" }
        val normalized = normalizePhoneNumber(phoneNumber)
        require(PHONE_PATTERN.matches(normalized)) { "Ungueltige Telefonnummer" }
        val tan = generateTan()
        val now = Instant.now()
        val setup = repository.save(AuthSmsSetup(normalized, tan, false, now, now))
        sendTestSms(normalized, tan)
        return AuthSmsEnrollResult(EnrollmentRef(setup.id), tan)
    }

    @Transactional
    fun confirmEnrollment(ref: EnrollmentRef, tan: String) {
        require(tan.isNotBlank()) { "ref und TAN sind erforderlich" }
        val setup = repository.findByIdOrNull(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            ?: throw IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}")
        if (setup.tan != tan.trim()) {
            throw IllegalArgumentException("Ungueltige TAN")
        }
        setup.validated = true
        setup.updatedAt = Instant.now()
        repository.save(setup)
    }

    @Transactional
    fun startChallenge(ref: EnrollmentRef): AuthSmsChallengeResult {
        val setup = repository.findByIdOrNull(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            ?: throw IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}")
        if (!setup.validated) {
            throw IllegalArgumentException("SMS-Enrollment wurde noch nicht validiert")
        }
        val tan = generateTan()
        setup.tan = tan
        setup.updatedAt = Instant.now()
        repository.save(setup)
        sendTestSms(setup.phoneNumber, tan)
        return AuthSmsChallengeResult(ref, tan)
    }

    @Transactional
    fun verifyChallenge(ref: EnrollmentRef, tan: String) {
        require(tan.isNotBlank()) { "ref und TAN sind erforderlich" }
        val setup = repository.findByIdOrNull(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            ?: throw IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}")
        if (setup.tan != tan.trim()) {
            throw IllegalArgumentException("Ungueltige TAN")
        }
    }

    fun isValidPhoneNumber(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) return false
        return PHONE_PATTERN.matches(normalizePhoneNumber(phoneNumber))
    }

    private fun normalizePhoneNumber(phoneNumber: String): String =
        phoneNumber.replace("\\s+".toRegex(), "").trim()

    private fun generateTan(): String = (RANDOM.nextInt(900_000) + 100_000).toString()

    private fun sendTestSms(phoneNumber: String?, tan: String?) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt.")
    }

    companion object {
        private val PHONE_PATTERN = "^\\+?[0-9]{6,20}$".toRegex()
        private val RANDOM = SecureRandom()
    }
}
