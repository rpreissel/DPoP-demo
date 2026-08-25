package com.example.dpop.auth_device.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EnrollDeviceToolDataRepository : JpaRepository<EnrollDeviceToolData, UUID>
