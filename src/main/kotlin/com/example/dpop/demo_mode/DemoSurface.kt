package com.example.dpop.demo_mode

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * A bean - in practice a controller - that only exists in demo mode (`demo.mode=true`, ADR-36): the
 * unauthenticated surfaces of the simulated foreign systems (the Personenverzeichnis issues
 * Freischaltcodes in plain text, the KOBIL and Nect stand-ins answer anyone) and the demo's own
 * switches. In an instance with real people they are not merely unused but absent - reachable, each
 * would be a way to take over accounts (review 2026-09, Phase F).
 *
 * `ApiBoundaryArchitectureTest` requires it on every controller of a simulated foreign system.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["demo.mode"], havingValue = "true")
annotation class DemoSurface
