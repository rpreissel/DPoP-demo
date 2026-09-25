package com.example.dpop.auth_kobil.api.v1

import com.example.dpop.texts.Text
import com.example.dpop.auth_kobil.AuthKobilDescriptor
import com.example.dpop.auth_kobil.internal.authkobil.AuthKobilToolHandler
import com.example.dpop.tool_api.AccountDirectory
import com.example.dpop.tool_api.BindingKey
import com.example.dpop.tool_api.ChannelResponse
import com.example.dpop.tool_api.PasswordCredentialPort
import com.example.dpop.tool_api.ToolEndpoint
import com.example.dpop.tool_spi.ToolOutcome
import com.example.dpop.tool_spi.UnresolvableReferenceException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.dpop.tool_api.API_V1

private const val AUTH_KOBIL_TOOL_ID = "auth-kobil"

data class AuthKobilPatchRequest(
    /** The one-time password KOBIL handed the app - a reference to an assertion, not the assertion. */
    @field:Schema(example = "48210937")
    val otp: String? = null,
)

/**
 * toolId=auth-kobil: two acts, and they get two URLs.
 *
 * Releasing the PIN is a creation - single-use, time-bounded, non-idempotent - and its response
 * is the only place the PIN ever appears. Folding it into the PATCH would mean deciding which act
 * a request is by inspecting which nullable fields happen to be set, and it would put the PIN into
 * step state that `ToolControllerSupport.buildReadResponse` rebuilds on every GET. A tool owning
 * its own sub-resources is explicitly allowed (docs/05-api.md); this is the first one to need it.
 */
@RestController
@Tag(name = "Tool: KOBIL")
@SecurityRequirement(name = "dpop")
class AuthKobilToolController(
    private val descriptor: AuthKobilDescriptor,
    private val handler: AuthKobilToolHandler,
    private val toolEndpoint: ToolEndpoint,
    private val accountDirectory: AccountDirectory,
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-kobil")
    @Operation(
        summary = "Activate auth-kobil",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-kobil", "step": "unlock", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "kobil-unlock", "unlockOptions": ["biometric", "password"], "tenantId": "dpop-demo", "kobilUserId": "kob-1a2b3c4d5e6f"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.beginActivation(channelSessionId, bindingKeyRef, AUTH_KOBIL_TOOL_ID)

        // Resolved at the call site, as auth-device does: only the instance whose details live on
        // THIS caller's key may be used, so a credential bound to another phone is not reachable.
        val enrollmentRef = context.accountId?.let { accountId ->
            accountDirectory.activeInstanceEnrollment(accountId, descriptor.method) { details ->
                descriptor.keyBinding?.livesOn(details, bindingKeyRef) == true
            }
        } ?: throw UnresolvableReferenceException(Text("Auf diesem Gerät ist KOBIL nicht als Anmeldeverfahren eingerichtet"))

        val outcome = handler.start(context.toolSessionId, enrollmentRef, passwordAvailable(context.accountId))
        val response = toolEndpoint.applyOutcome(context, outcome)
        val location = toolEndpoint.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PostMapping("$API_V1/tools/{toolSessionId}/auth-kobil/pin-releases")
    @Operation(
        summary = "Release the backend-held PIN",
        description = "The app presents either the locally stored unlock secret (guarded by its " +
            "biometric prompt) or the account password. The PIN is in this response and nowhere " +
            "else - a later GET will not return it again.",
        responses = [
            ApiResponse(
                responseCode = "201",
                description = "Released",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-kobil", "step": "otp", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "kobil-otp", "missingFields": ["otp"], "tenantId": "dpop-demo", "kobilUserId": "kob-1a2b3c4d5e6f", "kobilPin": "40318827"}
                    }
                """)])]
            ),
            ApiResponse(
                responseCode = "200",
                description = "Not released - an ordinary retryable failure, deliberately not an error status"
            )
        ]
    )
    fun releasePin(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody request: KobilPinReleaseRequest,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, AUTH_KOBIL_TOOL_ID)

        // Resolved even when the account has no password: PasswordCredentialPort.verify must run
        // either way so a missing credential costs exactly what a wrong one does.
        val passwordEnrollment = passwordEnrollmentOf(context.accountId)

        val outcome = handler.releasePin(toolSessionId, request.unlock, passwordEnrollment)
        val response = toolEndpoint.applyOutcome(context, outcome)
        val status = if (outcome is ToolOutcome.Failed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-kobil")
    @Operation(
        summary = "Redeem the one-time password",
        description = "The backend fetches the assertion behind the OTP from KOBIL, compares the " +
            "device identifier with the enrolled one and checks the reported risks.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["kobil", "biometric"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthKobilPatchRequest?,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadCurrent(toolSessionId, bindingKeyRef, AUTH_KOBIL_TOOL_ID)
        val outcome = handler.patch(toolSessionId, request?.otp)
        return ResponseEntity.ok(toolEndpoint.applyOutcome(context, outcome))
    }

    /**
     * The account's active password credential, or null - resolved here rather than in the
     * handler, which cannot see the account's other methods (module boundary). Used twice, for
     * two different questions: whether to OFFER the password unlock, and to verify one.
     */
    private fun passwordEnrollmentOf(accountId: Long?) =
        accountId?.let { accountDirectory.activeEnrollment(it, PasswordCredentialPort.METHOD) }

    private fun passwordAvailable(accountId: Long?) = passwordEnrollmentOf(accountId) != null

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-kobil")
    @Operation(summary = "Read the current auth-kobil state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
    ): ResponseEntity<ChannelResponse> {
        val context = toolEndpoint.loadContext(toolSessionId, bindingKeyRef, AUTH_KOBIL_TOOL_ID)
        val outcome = if (toolEndpoint.isCurrentTool(context)) {
            checkNotNull(handler.read(toolSessionId, passwordAvailable(context.accountId)) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolEndpoint.buildReadResponse(context, outcome))
    }
}
