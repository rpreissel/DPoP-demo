package com.example.dpop.mail_mock

import org.springframework.modulith.ApplicationModule

/**
 * The simulated mail server - a stand-in for a *foreign* system, like `kobil_mock`: it knows
 * nothing of ours, not even `texts`. `auth_email` calls [MailServer] directly (named exception in
 * docs/08-projektrahmen.md M-3, same pattern as ADR-31).
 *
 * Verified by `DpopApplicationTests.modulithStructureIsValid`.
 */
@ApplicationModule(allowedDependencies = [])
internal class ModuleMetadata
