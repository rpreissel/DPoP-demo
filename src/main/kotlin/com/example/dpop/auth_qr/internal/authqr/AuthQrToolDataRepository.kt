package com.example.dpop.auth_qr.internal.authqr

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthQrToolDataRepository : JpaRepository<AuthQrToolData, UUID>
