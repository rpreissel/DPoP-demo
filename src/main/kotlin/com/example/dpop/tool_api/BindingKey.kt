package com.example.dpop.tool_api

/**
 * Marks a controller method parameter that receives the caller's resolved DPoP binding key
 * (`bindingKeyRef: String`).
 *
 * The value is resolved and validated before the method body runs - a tool controller never
 * reads the DPoP header or a raw proof itself.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class BindingKey
