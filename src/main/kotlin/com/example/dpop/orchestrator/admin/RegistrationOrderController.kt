package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegistrationOrderState(val enrollFirst: Boolean)

/**
 * Runtime toggle for REGISTER's "Enrollment zuerst" experiment (`RegisterDispatchStrategy`,
 * docs/04-orchestrierung.md): switches every brand-new REGISTER journey between the ident-first
 * status quo (`enrollFirst = false`, the default) and the alternative order, without a redeploy.
 * A named endpoint for one flag rather than a generic flag API: the flag's meaning (which REGISTER
 * order) is the contract the frontend knows, `register-enroll-first` is only its storage key in
 * `orchestrator.feature_flag`. Behind the admin login like everything under [ADMIN_API].
 */
@RestController
@RequestMapping("$ADMIN_API/registration-order")
@Tag(name = "Admin: registration order", description = "Toggle between ident-first and enroll-first REGISTER")
class RegistrationOrderController(private val featureFlagService: FeatureFlagService) {

    @GetMapping
    @Operation(summary = "Current REGISTER order")
    fun get(): RegistrationOrderState = RegistrationOrderState(featureFlagService.isEnabled(FeatureFlags.REGISTER_ENROLL_FIRST))

    @PutMapping
    @Operation(summary = "Set REGISTER order", description = "Takes effect for the next brand-new REGISTER journey - a running one keeps whichever order it started with.")
    fun put(@RequestBody request: RegistrationOrderState) {
        featureFlagService.setEnabled(FeatureFlags.REGISTER_ENROLL_FIRST, request.enrollFirst)
    }
}
