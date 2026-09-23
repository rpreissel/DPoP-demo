package com.example.dpop.nect_mock.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NectCaseRepository : JpaRepository<NectCase, UUID>
