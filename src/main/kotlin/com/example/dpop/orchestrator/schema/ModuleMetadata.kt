package com.example.dpop.orchestrator.schema

/**
 * How the database schema comes into being: which migrations Flyway runs
 * ([ModuleMigrationLocations]) and what happens when a local database no longer matches them
 * ([FlywayResetConfig]).
 *
 * Its own package because neither belongs to any of the orchestrator's domain packages.
 * `FlywayResetConfig` sat in `session` before, which is about channel, journey and tool sessions
 * and has nothing to do with schema bootstrapping.
 *
 * Depends on nothing inside the orchestrator, and must not: it runs before any of it exists.
 */
internal object SchemaPackageMarker
