package com.example.dpop.kobil_mock.api.v1

import com.example.dpop.kobil_mock.KobilRejectedException
import com.example.dpop.kobil_mock.KobilRisk
import com.example.dpop.kobil_mock.KobilSsms
import com.example.dpop.kobil_mock.KobilUserRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class KobilActivateRequest(val tenantId: String, val userId: String, val activationCode: String, val pin: String)

data class KobilLoginRequest(val tenantId: String, val userId: String, val pin: String)

data class KobilOtpResponse(val otp: String)

data class KobilRiskSimulationRequest(val tenantId: String, val userId: String, val risks: Set<KobilRisk>)

/**
 * The face the APP talks to - KOBIL's own endpoint, standing in for what the MC SDK does natively
 * on a phone. It is deliberately NOT under `/orchestrator/api`: in reality the device speaks to
 * KOBIL directly, and routing it through our backend would quietly turn a third party into an
 * internal call, which is exactly the property this whole flow is meant to demonstrate.
 *
 * No DPoP, no channel, no journey: this service knows nothing about ours.
 */
@RestController
@RequestMapping("/mock-kobil")
@Tag(name = "Mock KOBIL", description = "Simuliertes KOBIL-Backend - kein Endpunkt dieser Anwendung, sondern der Fremddienst")
class KobilMockController(private val ssms: KobilSsms) {

    @PostMapping("activate")
    @Operation(
        summary = "MC SDK ActivateEvent",
        description = "Binds this device to the user and creates the device identifier. The " +
            "activation code is spent in the process."
    )
    fun activate(@RequestBody request: KobilActivateRequest): Map<String, String> {
        val deviceId = ssms.activate(
            KobilUserRef(request.tenantId, request.userId),
            request.activationCode,
            request.pin,
        )
        return mapOf("deviceId" to deviceId)
    }

    @PostMapping("login")
    @Operation(
        summary = "MC SDK LoginEvent",
        description = "Checks the device, files an assertion, and returns only the one-time " +
            "password that points at it. The assertion itself never reaches the caller."
    )
    fun login(@RequestBody request: KobilLoginRequest): KobilOtpResponse =
        KobilOtpResponse(ssms.login(KobilUserRef(request.tenantId, request.userId), request.pin))

    @PostMapping("simulate-risk")
    @Operation(
        summary = "Demo switch: what this device's sensors report from now on",
        description = "Has no counterpart in the real product. Without it the risk-rejection path " +
            "would only ever be reachable from a test."
    )
    fun simulateRisk(@RequestBody request: KobilRiskSimulationRequest): Map<String, Any> {
        ssms.simulateRiskSignals(KobilUserRef(request.tenantId, request.userId), request.risks)
        return mapOf("risks" to request.risks.map { it.name })
    }

    /** KOBIL answers for itself; its refusals are not this application's error contract. */
    @ExceptionHandler(KobilRejectedException::class)
    fun rejected(exception: KobilRejectedException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to exception.message))
}
