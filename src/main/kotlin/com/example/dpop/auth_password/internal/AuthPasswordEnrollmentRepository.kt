package com.example.dpop.auth_password.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AuthPasswordEnrollmentRepository : JpaRepository<AuthPasswordEnrollment, Long>
