package com.example.dpop.orchestrator

import com.example.dpop.orchestrator.journey.strategy.StrategyTestFixtures
import com.example.dpop.orchestrator.tool.ToolHandlerRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Pins the orchestrator integration test suite to today's exact tool set, deliberately isolated
 * from whichever modules happen to be registered in the real application context. Without this,
 * every `shouldContainExactlyInAnyOrder` candidate-list assertion across the suite - and the
 * default `availableTools` [IntegrationTestSupport] sends on every `POST /channels` - would
 * silently grow or shrink whenever a module is added or removed elsewhere in the app, breaking
 * tests that have nothing to do with the changed module.
 *
 * Reuses [StrategyTestFixtures.catalog] rather than listing the same descriptors a second time -
 * one pinned tool set for both the pure IntentStrategy unit tests and this HTTP-level suite.
 *
 * `@Primary` wins autowiring-by-type for the WHOLE application context these tests boot, not just
 * the [IntegrationTestSupport] helper field - so every real orchestrator component
 * (`JourneyService`, `DefaultAuthPolicy`, `CandidateTools`, ...) exercised during an integration
 * test run also sees this frozen catalog, not the real one.
 *
 * [com.example.dpop.orchestrator.tool.ToolCatalogStartStepTest] deliberately does NOT import this
 * - it exists specifically to pin the real, full Spring-collected catalog, and must keep seeing
 * every registered module.
 */
@TestConfiguration
class PinnedToolCatalogTestConfig {
    @Bean
    @Primary
    fun pinnedToolHandlerRegistry(): ToolHandlerRegistry = StrategyTestFixtures.catalog
}
