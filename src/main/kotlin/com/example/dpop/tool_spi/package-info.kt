/**
 * Shared tool contract (ToolDescriptor/ToolOutcome, docs/03-tool-architektur.md). Handler
 * classes implement ToolDescriptor directly; there is no separate wrapper interface.
 *
 * Not one of the M1-M5 modules in docs/08-projektrahmen.md: a pure SPI package with no
 * dependencies of its own, so that both the orchestrator and the method modules (id_fsc,
 * auth_sms) can depend on it without the method modules depending back on the orchestrator
 * (which "ist das einzige Modul, das die anderen Module referenzieren darf" - a module
 * that referenced orchestrator while orchestrator references it back would be a cycle).
 */
@file:org.springframework.modulith.ApplicationModule

package com.example.dpop.tool_spi
