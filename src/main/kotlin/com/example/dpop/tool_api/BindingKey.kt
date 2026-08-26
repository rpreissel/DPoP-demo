package com.example.dpop.tool_api

/**
 * Marks the controller parameter that receives the DPoP-proven device identity - resolved before
 * the method body runs, so no controller (tool or channel alike) ever touches the DPoP header or
 * the raw request for this itself (docs/04-orchestrierung.md #5).
 *
 * An annotation on a plain `String`, not a wrapper type: the app-wide `bindingKeyRef: String`
 * contract (`ChannelService`, `ChannelAccessGuard`, `DeviceAccountLink`, ...) needs no change at
 * all - only the controller boundary does. The resolving `HandlerMethodArgumentResolver` lives in
 * the orchestrator, which is the only place that may touch DPoP validation.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BindingKey
