package com.example.dpop.auth_qr

import com.example.dpop.tool_spi.FactorType
import com.example.dpop.tool_spi.MethodRole
import com.example.dpop.tool_spi.ToolDescriptor
import org.springframework.stereotype.Component
import java.time.Duration

/** Shared by enroll-qr/auth-qr/auth-qr-lookup/confirm-qr-login - the one place "qr" is spelled out. */
internal const val QR_METHOD = "qr"

/** The [com.example.dpop.tool_spi.EnrollmentRef.type] enroll-qr writes - a pure opt-in marker, no secret. */
internal const val QR_OPTIN_ENROLLMENT_TYPE = "auth_qr_optin"

/** How long a pairing request stays open (docs/07-betrieb.md #5 - not further validated). */
internal val QR_LOGIN_TTL: Duration = Duration.ofMinutes(5)

/**
 * `enroll-qr`: a reiner Opt-in-Marker, kein Geheimnis (docs/03-tool-architektur.md) - an
 * account must have this active before `auth-qr` is ever offered as a candidate, and before
 * `confirm-qr-login` may approve a pairing on its behalf.
 */
@Component
object EnrollQrDescriptor : ToolDescriptor {
    override val toolId = "enroll-qr"
    override val role = MethodRole.ENROLLMENT
    override val method = QR_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = "loa1"
}

/**
 * WEB-side, account already known via the channel (step-up / re-auth on a resolved account).
 *
 * `factorTypes` declares both POSSESSION and KNOWLEDGE, not POSSESSION alone: `maxAcr=loa2` is
 * only earned because `ConfirmPeerLoginStrategy.gate()` forces the APPROVING app's own session
 * through its own loa2 gate first (fresh MFA there) before it may approve anything here - the
 * proof this tool reports is never a bare "device present" claim, it *encapsulates* whatever
 * combination the app already had to prove. Declaring both factor types here is what makes this
 * tool self-contained MFA on its own (same pattern as a single `ident-eid` covering card+PIN),
 * consistent with `maxAcr=loa2` already being asserted directly rather than earned through this
 * session's own combination bump.
 */
@Component
object AuthQrDescriptor : ToolDescriptor {
    override val toolId = "auth-qr"
    override val role = MethodRole.IDENTIFIED_AUTH
    override val method = QR_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = "loa2"
    // Waits on the APP side, not a form input - never the role's own "auth" default.
    override val startStep = "waitForApp"
}

/** WEB-side, account unknown until the APP side's approval reveals it - a passwordless login. Same factorTypes reasoning as [AuthQrDescriptor]. */
@Component
object AuthQrLookupDescriptor : ToolDescriptor {
    override val toolId = "auth-qr-lookup"
    override val role = MethodRole.LOOKUP_AUTH
    override val method = QR_METHOD
    override val factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
    override val maxAcr = "loa2"
    override val startStep = "waitForApp"
}

/** APP-side: approves or declines a pending `auth-qr`/`auth-qr-lookup` pairing. */
@Component
object ConfirmQrLoginDescriptor : ToolDescriptor {
    override val toolId = "confirm-qr-login"
    override val role = MethodRole.PEER_APPROVAL
    override val method = QR_METHOD
    override val factorTypes = emptySet<FactorType>()
    override val maxAcr = "loa2"
}
