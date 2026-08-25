package com.example.dpop.auth_email

import org.springframework.modulith.ApplicationModule

/**
 * The one method module that may read `account` - deliberately, and declared here rather than
 * left to review.
 *
 * Every other method module owns a *swappable credential* and reaches the orchestrator through
 * `tool_spi` alone. The confirmed email is not that: it is the account's **identifier**. It lives
 * directly on `Account` (V6, `CREATE UNIQUE INDEX idx_account_email`) because `auth-sms-lookup`
 * and `auth-password-lookup` resolve an account from a submitted email without `auth_email` being
 * involved at all, and because `ToolDescriptor.requiresConfirmedEmail` gates `enroll-password` on
 * it. Six call sites across the app read it; exactly one procedure establishes it.
 *
 * That asymmetry is real, so the dependency it implies is modelled instead of hidden. It used to
 * be laundered through the orchestrator - `ToolOutcomeProcessor.handleEnrolled` carried an
 * `if (method == "email")` branch, and this module received account facts pre-chewed as handler
 * parameters. The module already consumed account knowledge; only the edge was invisible.
 *
 * Acyclic by construction: `account` declares `allowedDependencies = ["tool_spi"]`, so it can
 * never depend back on a method module.
 *
 * Do not copy this exemption. If a second module needs it, that is a signal to revisit whether
 * the attribute belongs on `Account` at all - not a precedent (docs/03-tool-architektur.md #2).
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "account"])
internal class ModuleMetadata
