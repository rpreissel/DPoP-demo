package com.example.dpop.kobil_mock

import org.springframework.modulith.ApplicationModule

/**
 * The simulated KOBIL backend - a stand-in for a *foreign* system, not a tool module. It therefore
 * depends on nothing at all: it knows no `tool_spi`, no `tool_api`, no account, and above all
 * nothing about the journey it happens to take part in. That empty dependency list is what keeps
 * the demo honest; the moment this module could read our side, the flow would stop demonstrating
 * anything.
 *
 * It has two faces, as the real service does: an HTTP one the app talks to directly
 * (`kobil_mock.api.v1`, the MC SDK's counterpart) and [KobilSsms], the management/verification
 * surface our own backend calls.
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = [])
internal class ModuleMetadata
