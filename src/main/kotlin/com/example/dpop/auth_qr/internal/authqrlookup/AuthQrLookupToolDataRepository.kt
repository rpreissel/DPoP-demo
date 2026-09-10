package com.example.dpop.auth_qr.internal.authqrlookup

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthQrLookupToolDataRepository : JpaRepository<AuthQrLookupToolData, UUID>
