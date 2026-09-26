package com.example.dpop.orchestrator.admin

import com.example.dpop.orchestrator.session.AttemptCounter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter

/** The one operator login (`demo.admin.*` in application.yml) - demo values, overridable per environment. */
@ConfigurationProperties(prefix = "demo.admin")
data class AdminCredentials(val username: String, val password: String)

/**
 * Spring Security guards exactly one thing: the operator endpoints under [ADMIN_API]. Everything
 * else keeps authenticating itself the way it always has - DPoP proofs for the app channel, the
 * Keycloak peer-auth JWT for the facade - and passes through the second chain untouched.
 *
 * HTTP Basic, stateless, without the `WWW-Authenticate` challenge: a 401 goes back to the admin
 * page's own login form instead of popping up the browser's native dialog.
 */
@Configuration
class AdminSecurityConfig {

    @Bean
    @Order(1)
    fun adminChain(http: HttpSecurity, attemptCounter: AttemptCounter): SecurityFilterChain =
        http
            .securityMatcher("$ADMIN_API/**")
            .addFilterBefore(AdminLoginThrottleFilter(attemptCounter), BasicAuthenticationFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().hasRole(ADMIN_ROLE) }
            .httpBasic { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .build()

    /**
     * Everything else: open, as before Spring Security was on the classpath. CSRF off because no
     * endpoint here relies on a cookie session; frames from the same origin so the H2 console
     * (a deliberate project decision, see application.yml) keeps working.
     */
    @Bean
    @Order(2)
    fun openChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }
            .headers { headers -> headers.frameOptions { it.sameOrigin() } }
            .build()

    @Bean
    fun adminUsers(credentials: AdminCredentials): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername(credentials.username)
                // An encoded value ("{bcrypt}...", "{argon2}...") is used as is; plain text only in
                // demo mode - ProductionModeCheck refuses it outside (review 2026-09-26, F-7).
                .password(if (credentials.password.startsWith("{")) credentials.password else "{noop}${credentials.password}")
                .roles(ADMIN_ROLE)
                .build()
        )

    private companion object {
        const val ADMIN_ROLE = "ADMIN"
    }
}
