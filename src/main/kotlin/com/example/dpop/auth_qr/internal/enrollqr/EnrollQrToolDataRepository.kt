package com.example.dpop.auth_qr.internal.enrollqr

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EnrollQrToolDataRepository : JpaRepository<EnrollQrToolData, UUID>
