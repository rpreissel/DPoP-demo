package com.example.dpop.auth_device.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceEnrollmentRepository : JpaRepository<DeviceEnrollment, Long>
