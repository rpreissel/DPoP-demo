package com.example.dpop.auth_email

import org.springframework.modulith.ApplicationModule

/**
 * The first anchor-role application of the claims model
 * (docs/ideen/claims-modell-und-vertrauensanker.md): the confirmed email is the account's
 * **identifier**, not a swappable credential. It is asserted as a typed EMAIL claim on
 * `Completed.Enrolled` and recorded through the generic claims write path
 * (`AccountService.recordClaim`), which consolidates the `account_anchor` row and fires the
 * `AccountChanged` event. Reads go through the generic anchor
 * ports (`AccountDirectory.resolveByAnchor`/`anchorValue`) - the same normalized lookups
 * `auth-sms-lookup`/`auth-password-lookup` and `enroll-password`'s `requires` gate use.
 * `auth-sms-lookup` and `auth-password-lookup` resolve by email through the port without this
 * module being involved at all.
 *
 * A method module talks to the orchestrator through tool_spi and tool_api only - it never
 * reads account or another method module (docs/03-tool-architektur.md #2). `tool_api` is safe
 * alongside: it is the shared SPI, not a method module, and does not depend back on
 * `account`. Its controllers (`auth_email.api.v1`) live here too, reaching the orchestrator
 * through `tool_api.ToolEndpoint`/`AccountDirectory` alone (docs/04-orchestrierung.md #5) -
 * the orchestrator never needs to know `auth_email` exists.
 *
 * Acyclic by construction: `account` declares `allowedDependencies = ["tool_spi", "tool_api"]`,
 * so it can never depend back on a method module.
 */
@ApplicationModule(allowedDependencies = ["tool_spi", "tool_api"])
internal class ModuleMetadata
