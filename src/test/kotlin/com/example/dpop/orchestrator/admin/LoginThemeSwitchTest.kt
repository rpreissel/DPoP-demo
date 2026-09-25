package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.KeycloakRealmLoginTheme
import com.example.dpop.orchestrator.kc.LoginTheme
import com.example.dpop.orchestrator.kernel.FeatureFlags
import com.example.dpop.orchestrator.session.FeatureFlagService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

/**
 * The switch's own rules - the realm write (Keycloak admin API) is mocked here; the compose stack
 * exercises it for real. Realm first, flag second, so a refused write changes nothing; and a
 * failed alignment at start never stops the orchestrator.
 */
class LoginThemeSwitchTest {

    private val flags = mockk<FeatureFlagService>(relaxed = true)
    private val realm = mockk<KeycloakRealmLoginTheme>(relaxed = true)
    private val switch = LoginThemeSwitch(flags, realm)

    @Test
    fun `no flag means FreeMarker, the flag means Keycloakify`() {
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY) } returns false
        assertThat(switch.current()).isEqualTo(LoginTheme.FREEMARKER)
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY) } returns true
        assertThat(switch.current()).isEqualTo(LoginTheme.KEYCLOAKIFY)
    }

    @Test
    fun `switching writes the realm first, then remembers the choice`() {
        switch.switchTo(LoginTheme.KEYCLOAKIFY)
        verifyOrder {
            realm.apply(LoginTheme.KEYCLOAKIFY)
            flags.setEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY, true)
        }
    }

    @Test
    fun `a refused realm write leaves the flag as it was`() {
        every { realm.apply(any()) } throws IllegalStateException("Keycloak sagt nein")
        assertThatThrownBy { switch.switchTo(LoginTheme.KEYCLOAKIFY) }.hasMessage("Keycloak sagt nein")
        verify(exactly = 0) { flags.setEnabled(any(), any(), any()) }
    }

    @Test
    fun `at start the realm follows the flag, and a failure there does not stop the start`() {
        every { flags.isEnabled(FeatureFlags.KEYCLOAK_LOGIN_KEYCLOAKIFY) } returns true
        switch.run(DefaultApplicationArguments())
        verify { realm.apply(LoginTheme.KEYCLOAKIFY) }

        every { realm.apply(any()) } throws IllegalStateException("Keycloak nicht erreichbar")
        switch.run(DefaultApplicationArguments())
    }
}
