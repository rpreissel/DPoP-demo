package com.example.dpop.auth_email

import org.springframework.modulith.ApplicationModule

/**
 * The first anchor-role application of the claims model
 * (docs/ideen/claims-modell-und-vertrauensanker.md): the confirmed email is the account's
 * **identifier**, not a swappable credential. It is asserted as a typed EMAIL claim on
 * `Completed.Enrolled` and recorded through the generic claims write path
 * (`AccountService.recordClaim`), which consolidates the canonical projection column, the
 * `account_anchor` row and the `AccountChanged` event. Reads go through the generic anchor
 * ports (`AccountDirectory.resolveByAnchor`/`anchorValue`) - the same normalized lookups
 * `auth-sms-lookup`/`auth-password-lookup` and `enroll-password`'s `requires` gate use.
 *
 * The former `auth_email -> account` exemption is therefore gone. It existed because the
 * anchor role was unnamed: the use/lookup handlers read full `AccountProfile` data directly
 * while the value itself was laundered through the orchestrator (an `if (method == "email")`
 * branch in the generic outcome handler, pre-chewed account facts as handler parameters).
 * With the role named and typed, this module hangs on `tool_spi`/`tool_api` alone, exactly
 * like every other method module - `auth-sms-lookup` and `auth-password-lookup` resolve by
 * email through the port without this module being involved at all.
 *
 * Acyclic by construction: `account` declares `allowedDependencies = ["tool_spi", "tool_api"]`,
 * so it can never depend back on a method module.
 */
/**
 * `tool_api` is safe alongside - it is the shared SPI, not a method module, and does not depend
 * back on `account`. Its controllers (`auth_email.api.v1`) live here too, reaching the
 * orchestrator through `tool_api.ToolEndpoint`/`AccountDirectory` alone
 * (docs/04-orchestrierung.md #5, DPoP-demo-2tm) - the orchestrator no longer needs to know
 * `auth_email` exists.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api"])
internal class ModuleMetadata
