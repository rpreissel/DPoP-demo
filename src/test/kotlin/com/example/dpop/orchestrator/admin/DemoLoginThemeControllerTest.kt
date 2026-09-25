package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.kc.LoginTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The public switch is the admin one without the login - it must reach the very same
 * [LoginThemeSwitch], so both pages always agree on which theme is active.
 */
class DemoLoginThemeControllerTest {

    private val switch = mockk<LoginThemeSwitch>(relaxed = true)
    private val controller = DemoLoginThemeController(switch)

    @Test
    fun `reads and switches through the same switch as the admin endpoint`() {
        every { switch.current() } returns LoginTheme.KEYCLOAKIFY
        assertThat(controller.get()).isEqualTo(LoginThemeState(LoginTheme.KEYCLOAKIFY))

        controller.put(LoginThemeState(LoginTheme.FREEMARKER))
        verify { switch.switchTo(LoginTheme.FREEMARKER) }
    }
}
