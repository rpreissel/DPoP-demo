package com.example.dpop.auth_password

/**
 * Shared by enroll/auth/auth-lookup: this demo never generates a random credential the way
 * auth-sms/auth-email do with a TAN, so all three handlers prefill the same fixed value via
 * `demoPassword` (ToolControllerSupport.applyOutcome, mirroring the demoTan convention) - a
 * tester can click through enrollment and both login variants without ever having to remember
 * a password they typed once.
 */
internal const val DEMO_PASSWORD = "correct-horse-battery"
