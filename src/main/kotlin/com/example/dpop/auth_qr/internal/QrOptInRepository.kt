package com.example.dpop.auth_qr.internal

import org.springframework.data.jpa.repository.JpaRepository

interface QrOptInRepository : JpaRepository<QrOptIn, Long>
