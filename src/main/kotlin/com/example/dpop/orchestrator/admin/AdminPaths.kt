package com.example.dpop.orchestrator.admin

/**
 * Where operator endpoints live - deliberately NOT under `API_V1`.
 *
 * The app contract (`api/openapi.yaml`, frozen as `api/published/v1.yaml`) is everything under
 * `API_V1`, and nothing else (`ModuleApiGroups.CONTRACT_GROUP`). These endpoints switch things for
 * the whole deployment - a tool's availability, the registration order - and an app client must
 * never depend on them. Under `API_V1` they were part of the frozen contract, and dropping them
 * later would have been reported as a breaking change to the app API.
 *
 * Not versioned with the app contract: operators and apps change on different schedules.
 */
const val ADMIN_API = "/orchestrator/admin"

/**
 * Public demo endpoints (e.g. the welcome page's server status) - outside the app contract like
 * [ADMIN_API], but without its login. Read-only, with one named exception: the login theme
 * ([DemoLoginThemeController]), which only changes how Keycloak's pages look and is offered to
 * every visitor on purpose so the two themes can be compared.
 */
const val DEMO_API = "/orchestrator/demo"
