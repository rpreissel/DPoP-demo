package com.example.dpop.auth_qr.internal.confirmqrlogin

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConfirmQrLoginToolDataRepository : JpaRepository<ConfirmQrLoginToolData, UUID>
