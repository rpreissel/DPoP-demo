package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.LoginTheme
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginThemeState(val theme: LoginTheme)

/**
 * Runtime switch between the FreeMarker and the Keycloakify login theme
 * (docs/ideen/keycloakify-statt-freemarker.md) - realm-wide, every client, step-up and the
 * manage-methods required action alike, from the next page Keycloak renders. A named endpoint like
 * [RegistrationOrderController]: the meaning is the contract, the flag only its storage. Exists
 * only under the `keycloak` profile - without Keycloak there is no login page to theme.
 */
@RestController
@RequestMapping("$ADMIN_API/login-theme")
@Tag(name = "Admin: login theme", description = "Switch Keycloak's login pages between FreeMarker and Keycloakify")
@Profile("keycloak")
class LoginThemeController(private val loginThemeSwitch: LoginThemeSwitch) {

    @GetMapping
    @Operation(summary = "Current login theme")
    fun get(): LoginThemeState = LoginThemeState(loginThemeSwitch.current())

    @PutMapping
    @Operation(
        summary = "Set the login theme",
        description = "Sets the realm's login theme in Keycloak first, then remembers it - if Keycloak refuses, nothing changes."
    )
    fun put(@RequestBody request: LoginThemeState) {
        loginThemeSwitch.switchTo(request.theme)
    }
}
