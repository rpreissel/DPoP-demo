package com.example.dpop.auth_email

import org.springframework.modulith.ApplicationModule

/**
 * One of the few method modules that may read `account` - deliberately, and declared here rather
 * than left to review.
 *
 * Every other method module owns a *swappable credential* and reaches the orchestrator through
 * `tool_spi` alone. The confirmed email is not that: it is the account's **identifier**. It lives
 * directly on `Account` (V6, `CREATE UNIQUE INDEX idx_account_email`) because `auth-sms-lookup`
 * and `auth-password-lookup` resolve an account from a submitted email without `auth_email` being
 * involved at all, and because `enroll-password`'s `ToolDescriptor.requires` (EMAIL at PROVEN)
 * gates on it.
 *
 * That asymmetry is real, so the dependency it implies is modelled instead of hidden. It used to
 * be laundered through the orchestrator - the generic outcome handler carried an
 * `if (method == "email")` branch, and this module received account facts pre-chewed as handler
 * parameters. The module already consumed account knowledge; only the edge was invisible.
 *
 * Acyclic by construction: `account` declares `allowedDependencies = ["tool_spi"]`, so it can
 * never depend back on a method module.
 *
 * This is a real, load-bearing exemption for `AuthEmailLookupToolHandler`/`AuthEmailUseToolHandler`,
 * which still read full `AccountProfile` data `AccountDirectory` deliberately doesn't expose - not
 * a precedent to copy freely (docs/03-tool-architektur.md #2). It does NOT mean this module is the
 * only caller of `AccountService.confirmEmail` - see that method's own doc: Spring Modulith has no
 * method-level access control, only a module-level one (`allowedDependencies`), so `JourneyService`
 * (generic `Action.AdoptCredential` handling) and `demo_seed` (bootstrap seeding) call it directly
 * too, each for its own unrelated reason. `EnrollEmailToolHandler` itself no longer writes the
 * confirmed email at all - it hands it through in `Completed.Enrolled.auditDetails`
 * (`CONFIRMED_EMAIL_AUDIT_KEY`), gated by the equally generic `ToolDescriptor.confirmsAccountEmail`.
 */
/**
 * `tool_api` is safe alongside - it is the shared SPI, not a method module, and does not depend
 * back on `account`. Its controllers (`auth_email.api.v1`) live here too, reaching the
 * orchestrator through `tool_api.ToolEndpoint`/`AccountDirectory` alone
 * (docs/04-orchestrierung.md #5, DPoP-demo-2tm) - the orchestrator no longer needs to know
 * `auth_email` exists.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api", "account"])
internal class ModuleMetadata
