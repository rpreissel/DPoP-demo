package com.example.dpop.auth_password

/**
 * Shared by enroll/auth/auth-lookup: this demo never generates a random credential the way
 * auth-sms/auth-email do with a TAN, so all three handlers prefill the same fixed value via
 * `demoPassword` (ToolControllerSupport.applyOutcome, mirroring the demoTan convention) - a
 * tester can click through enrollment and both login variants without ever having to remember
 * a password they typed once.
 *
 * Same literal as `KcDemoAccountSeeder.DEMO_PASSWORD` (auth_email, DPoP-demo-25q) - one demo
 * password project-wide, not two competing conventions. Duplicated as a literal rather than
 * imported: `auth_email` may not depend on `auth_password` directly (module boundary).
 */
internal const val DEMO_PASSWORD = "Demo1234!"
