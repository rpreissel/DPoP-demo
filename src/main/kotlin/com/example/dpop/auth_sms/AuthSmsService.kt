package com.example.dpop.auth_sms

import com.example.dpop.auth_sms.internal.AuthSmsSetup
import com.example.dpop.auth_sms.internal.AuthSmsSetupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.regex.Pattern

@Service
class AuthSmsService(private val repository: AuthSmsSetupRepository) {

    @Transactional
    fun startEnrollment(phoneNumber: String): AuthSmsEnrollResult {
        if (phoneNumber.isBlank()) {
            throw IllegalArgumentException("Telefonnummer ist erforderlich")
        }
        val normalized = normalizePhoneNumber(phoneNumber)
        if (!PHONE_PATTERN.matcher(normalized).matches()) {
            throw IllegalArgumentException("Ungueltige Telefonnummer")
        }
        val tan = generateTan()
        val now = Instant.now()
        val setup = repository.save(AuthSmsSetup(normalized, tan, false, now, now))
        sendTestSms(normalized, tan)
        return AuthSmsEnrollResult(EnrollmentRef(setup.id), tan)
    }

    @Transactional
    fun confirmEnrollment(ref: EnrollmentRef, tan: String) {
        requireNotNull(ref) { "ref und TAN sind erforderlich" }
        require(tan.isNotBlank()) { "ref und TAN sind erforderlich" }
        val setup = repository.findById(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            .orElseThrow { IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}") }
        if (setup.tan != tan.trim()) {
            throw IllegalArgumentException("Ungueltige TAN")
        }
        setup.validated = true
        setup.updatedAt = Instant.now()
        repository.save(setup)
    }

    @Transactional
    fun startChallenge(ref: EnrollmentRef): AuthSmsChallengeResult {
        val setup = repository.findById(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            .orElseThrow { IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}") }
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
        requireNotNull(ref) { "ref und TAN sind erforderlich" }
        require(tan.isNotBlank()) { "ref und TAN sind erforderlich" }
        val setup = repository.findById(ref.id ?: throw IllegalArgumentException("Ungueltige Enrollment-Ref"))
            .orElseThrow { IllegalArgumentException("SMS-Enrollment nicht gefunden: ${ref.id}") }
        if (setup.tan != tan.trim()) {
            throw IllegalArgumentException("Ungueltige TAN")
        }
    }

    fun isValidPhoneNumber(phoneNumber: String?): Boolean {
        if (phoneNumber.isNullOrBlank()) return false
        return PHONE_PATTERN.matcher(normalizePhoneNumber(phoneNumber)).matches()
    }

    private fun normalizePhoneNumber(phoneNumber: String): String =
        phoneNumber.replace("\\s+".toRegex(), "").trim()

    private fun generateTan(): String = (RANDOM.nextInt(900_000) + 100_000).toString()

    private fun sendTestSms(phoneNumber: String?, tan: String?) {
        println("[MOCK SMS] TAN $tan an $phoneNumber versandt.")
    }

    companion object {
        private val PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{6,20}$")
        private val RANDOM = SecureRandom()
    }
}
