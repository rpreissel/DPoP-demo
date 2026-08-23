package com.example.dpop.auth_sms.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthSmsEnrollmentRepository : JpaRepository<AuthSmsEnrollment, Long>
