/* tslint:disable */
/* eslint-disable */
/**
 * One active authentication method instance. `id` addresses it for DELETE .../methods/{id} - method name alone isn't unique when a method allows multiple instances (e.g. several active `device` entries, one per physical device). `label` is a user-chosen display name, set only for multi-instance methods; `null` for singleton ones (email/sms/password), which the client labels from `method` itself. `enrolledUnderAcr`/`maxAcr`/`effectiveAcr`/`factorTypes` surface the ADR-5 three-way cap (docs/12-entscheidungen.md): `effectiveAcr` is `min(enrolledUnderAcr, maxAcr)`, the level this method can actually contribute right now, which can be lower than the tool's own declared `maxAcr` if it was enrolled while the session had proven less.
 * @export
 * @interface ActiveMethodView
 */
export interface ActiveMethodView {
    /**
     * min(enrolledUnderAcr, maxAcr) - what this method actually contributes today.
     * @type {string}
     * @memberof ActiveMethodView
     */
    effectiveAcr?: string;
    /**
     * The level the session had already proven at the moment this method was enrolled (ADR-5) - caps effectiveAcr below maxAcr if lower.
     * @type {string}
     * @memberof ActiveMethodView
     */
    enrolledUnderAcr?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof ActiveMethodView
     */
    factorTypes?: Array<ActiveMethodViewFactorTypesEnum>;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    id: string;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    label?: string;
    /**
     * This tool's own declared ceiling - never account/session-specific.
     * @type {string}
     * @memberof ActiveMethodView
     */
    maxAcr?: string;
    /**
     * 
     * @type {string}
     * @memberof ActiveMethodView
     */
    method: string;
}


/**
 * @export
 */
export const ActiveMethodViewFactorTypesEnum = {
    KNOWLEDGE: 'KNOWLEDGE',
    POSSESSION: 'POSSESSION',
    INHERENCE: 'INHERENCE'
} as const;
export type ActiveMethodViewFactorTypesEnum = typeof ActiveMethodViewFactorTypesEnum[keyof typeof ActiveMethodViewFactorTypesEnum];

/**
 * One native authenticator proof - which authenticator TYPE, and which specific execution/instance of it.
 * @export
 * @interface AmrEntry
 */
export interface AmrEntry {
    /**
     * 
     * @type {string}
     * @memberof AmrEntry
     */
    amrSourceId: string;
    /**
     * 
     * @type {string}
     * @memberof AmrEntry
     */
    nativeToolId: string;
}
/**
 * An answer to whatever the current step is waiting on instead of a tool run (docs/04-orchestrierung.md #3) - e.g. "accept"/"decline" for the optional device-binding offer of a lookup login. Which values are valid depends on what next.step is currently offering.
 * @export
 * @interface AnswerRequest
 */
export interface AnswerRequest {
    /**
     * 
     * @type {string}
     * @memberof AnswerRequest
     */
    answer: string;
}
/**
 * 
 * @export
 * @interface AuthData
 */
export interface AuthData {
    /**
     * 
     * @type {number}
     * @memberof AuthData
     */
    accountId?: number;
    /**
     * 
     * @type {string}
     * @memberof AuthData
     */
    acr?: string;
    /**
     * Method -> who proved it: "orchestrator" for a completed orchestrator tool, "kc" for evidence a native Keycloak authenticator already established (docs/05-api.md Abschnitt 3). Informational only - the orchestrator alone still resolves the combined acr above, regardless of source.
     * @type {{ [key: string]: string; }}
     * @memberof AuthData
     */
    amr?: { [key: string]: string; };
}
/**
 * 
 * @export
 * @interface AuthEmailLookupPatchRequest
 */
export interface AuthEmailLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthEmailLookupPatchRequest
     */
    code?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthEmailLookupPatchRequest
     */
    email?: string;
}
/**
 * 
 * @export
 * @interface AuthEmailPatchRequest
 */
export interface AuthEmailPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthEmailPatchRequest
     */
    code?: string;
}
/**
 * 
 * @export
 * @interface AuthKobilPatchRequest
 */
export interface AuthKobilPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthKobilPatchRequest
     */
    otp?: string;
}
/**
 * 
 * @export
 * @interface AuthPasswordLookupPatchRequest
 */
export interface AuthPasswordLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordLookupPatchRequest
     */
    email?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordLookupPatchRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface AuthPasswordPatchRequest
 */
export interface AuthPasswordPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthPasswordPatchRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface AuthSmsLookupPatchRequest
 */
export interface AuthSmsLookupPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthSmsLookupPatchRequest
     */
    email?: string;
    /**
     * 
     * @type {string}
     * @memberof AuthSmsLookupPatchRequest
     */
    tan?: string;
}
/**
 * 
 * @export
 * @interface AuthSmsPatchRequest
 */
export interface AuthSmsPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof AuthSmsPatchRequest
     */
    tan?: string;
}
/**
 * 
 * @export
 * @interface BiometricUnlock
 */
export interface BiometricUnlock extends KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof BiometricUnlock
     */
    unlockSecret: string;
}


/**
 * Whether this device's DPoP key is already linked to an account (DeviceAccountLink, docs/02-domaenenmodell.md #1) - a pure read, no channel/journey created. Lets the entry screen show "this device belongs to X" before the user picks how to start.
 * @export
 * @interface BoundCredentialView
 */
export interface BoundCredentialView {
    /**
     * 
     * @type {string}
     * @memberof BoundCredentialView
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof BoundCredentialView
     */
    reference: string;
}
/**
 * 
 * @export
 * @interface ChannelBlock
 */
export interface ChannelBlock {
    /**
     * All active authentication methods on the account, regardless of whether this session's currentAmr proved them. currentAmr is session evidence (what THIS channel actually proved); activeMethods is the account's full standing method list, unfiltered by device - a lost/stolen device's credential must be removable from any authenticated session, not only from that device itself.
     * @type {Array<ActiveMethodView>}
     * @memberof ChannelBlock
     */
    activeMethods?: Array<ActiveMethodView>;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    channelSessionId: string;
    /**
     * Which facade this channel was opened through - APP (DPoP) or KEYCLOAK (docs/02-domaenenmodell.md Abschnitt 1). Fixed for the channel's whole lifetime.
     * @type {string}
     * @memberof ChannelBlock
     */
    channelType: string;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    currentAcr?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof ChannelBlock
     */
    currentAmr?: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof ChannelBlock
     */
    state: string;
}
/**
 * requiredAcr is a lower bound only. Always creates a brand-new ChannelSession for this device (docs/02-domaenenmodell.md #3) - DPoP proves the device, never a lookup key for resuming a session. To end a previous session first (logout), call DELETE .../channels/{channelSessionId} before this.
 * @export
 * @interface ChannelCreateRequest
 */
export interface ChannelCreateRequest {
    /**
     * toolIds this client supports and has enabled (docs/03-tool-architektur.md, availability) - e.g. GET /tools/catalog minus whatever the user turned off locally. Fixed for this channel's whole lifetime; a candidate list never offers a toolId outside this set, and activating one directly fails too.
     * @type {Array<string>}
     * @memberof ChannelCreateRequest
     */
    availableTools?: Array<string>;
    /**
     * The entry intent's own name, case-insensitively (AuthIntent.fromRequest) - no separate wire vocabulary. Omitted/fast_access (default): DeviceAccountLink found -> LOGIN, else REGISTRATION. lookup_login: always offers lookup-based login (email + credential), even on a linked device. register: always starts fresh REGISTRATION, even on a linked device (second account).
     * @type {string}
     * @memberof ChannelCreateRequest
     */
    intent?: ChannelCreateRequestIntentEnum;
    /**
     * 
     * @type {string}
     * @memberof ChannelCreateRequest
     */
    requiredAcr?: string;
}


/**
 * @export
 */
export const ChannelCreateRequestIntentEnum = {
    fast_access: 'fast_access',
    register: 'register',
    lookup_login: 'lookup_login'
} as const;
export type ChannelCreateRequestIntentEnum = typeof ChannelCreateRequestIntentEnum[keyof typeof ChannelCreateRequestIntentEnum];

/**
 * Raises the channel's durable required-ACR floor; the step-up trigger of the App channel (docs/05-api.md #9).
 * @export
 * @interface ChannelPatchRequest
 */
export interface ChannelPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ChannelPatchRequest
     */
    requiredAcr: string;
}
/**
 * 
 * @export
 * @interface ChannelResponse
 */
export interface ChannelResponse {
    /**
     * KEYCLOAK channels only (docs/05-api.md Abschnitt 3) - never present for APP.
     * @type {AuthData}
     * @memberof ChannelResponse
     */
    authData?: AuthData;
    /**
     * 
     * @type {ChannelBlock}
     * @memberof ChannelResponse
     */
    channel: ChannelBlock;
    /**
     * Demo-only correlation IDs, never part of the production contract.
     * @type {DemoInfo}
     * @memberof ChannelResponse
     */
    demo?: DemoInfo;
    /**
     * 
     * @type {Next}
     * @memberof ChannelResponse
     */
    next?: Next;
    /**
     * Whatever the current step needs to render. `kind` names the shape - see StepData.
     * @type {StepData}
     * @memberof ChannelResponse
     */
    stepData?: StepData;
}
/**
 * 
 * @export
 * @interface Confirm
 */
export interface Confirm extends Prompt {
    /**
     * 
     * @type {string}
     * @memberof Confirm
     */
    cancelLabel: string;
    /**
     * 
     * @type {string}
     * @memberof Confirm
     */
    confirmLabel: string;
    /**
     * 
     * @type {boolean}
     * @memberof Confirm
     */
    destructive?: boolean;
}
/**
 * 
 * @export
 * @interface ConfirmEmailPatchRequest
 */
export interface ConfirmEmailPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmEmailPatchRequest
     */
    code?: string;
    /**
     * 
     * @type {string}
     * @memberof ConfirmEmailPatchRequest
     */
    email?: string;
}
/**
 * 
 * @export
 * @interface ConfirmQrLoginActivateRequest
 */
export interface ConfirmQrLoginActivateRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginActivateRequest
     */
    pairingCode?: string;
}
/**
 * 
 * @export
 * @interface ConfirmQrLoginPatchRequest
 */
export interface ConfirmQrLoginPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginPatchRequest
     */
    decision?: string;
    /**
     * 
     * @type {string}
     * @memberof ConfirmQrLoginPatchRequest
     */
    pairingCode?: string;
}
/**
 * The step waits for a yes/no answer; the prompt is authored by the backend.
 * @export
 * @interface ConfirmStep
 */
export interface ConfirmStep {
    /**
     * 
     * @type {Prompt}
     * @memberof ConfirmStep
     */
    prompt: Prompt;
}
/**
 * 
 * @export
 * @interface DemoInfo
 */
export interface DemoInfo {
    [key: string]: any | any;
    /**
     * 
     * @type {number}
     * @memberof DemoInfo
     */
    accountId?: number;
    /**
     * The running journey chain for this channel, outermost first - see [JourneyDebugStep]. Empty once nothing is running.
     * @type {Array<JourneyDebugStep>}
     * @memberof DemoInfo
     */
    journeys?: Array<JourneyDebugStep>;
    /**
     * 
     * @type {number}
     * @memberof DemoInfo
     */
    personId?: number;
}
/**
 * 
 * @export
 * @interface DeviceLinkResponse
 */
export interface DeviceLinkResponse {
    /**
     * 
     * @type {number}
     * @memberof DeviceLinkResponse
     */
    accountId?: number;
    /**
     * Demo-only: what else this device is known by - one entry per key-bound credential of the linked account living on THIS key, with the reference its own method discloses (docs/09-dpop.md). The `device` method names its credential key, `kobil` the identifier the provider gave this phone. Absence is meaningful: a client that holds local data for a method no longer listed here is holding something stale.
     * @type {Array<BoundCredentialView>}
     * @memberof DeviceLinkResponse
     */
    boundCredentials?: Array<BoundCredentialView>;
    /**
     * 
     * @type {boolean}
     * @memberof DeviceLinkResponse
     */
    linked: boolean;
    /**
     * Demo-only, like ID-Token-Claims' name (docs/05-api.md) - who this device is linked to.
     * @type {string}
     * @memberof DeviceLinkResponse
     */
    personName?: string;
}
/**
 * 
 * @export
 * @interface DeviceProofPatchRequest
 */
export interface DeviceProofPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof DeviceProofPatchRequest
     */
    deviceProof?: string;
    /**
     * 
     * @type {string}
     * @memberof DeviceProofPatchRequest
     */
    label?: string;
}
/**
 * 
 * @export
 * @interface EnrollKobilPatchRequest
 */
export interface EnrollKobilPatchRequest {
    /**
     * 
     * @type {boolean}
     * @memberof EnrollKobilPatchRequest
     */
    activated?: boolean;
    /**
     * 
     * @type {boolean}
     * @memberof EnrollKobilPatchRequest
     */
    biometricConsent?: boolean;
    /**
     * 
     * @type {string}
     * @memberof EnrollKobilPatchRequest
     */
    label?: string;
}
/**
 * 
 * @export
 * @interface EnrollPasswordPatchRequest
 */
export interface EnrollPasswordPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof EnrollPasswordPatchRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface EnrollSmsPatchRequest
 */
export interface EnrollSmsPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsPatchRequest
     */
    phoneNumber?: string;
    /**
     * 
     * @type {string}
     * @memberof EnrollSmsPatchRequest
     */
    tan?: string;
}
/**
 * The attempt failed; retries remain.
 * @export
 * @interface FailedAttemptStep
 */
export interface FailedAttemptStep {
    /**
     * 
     * @type {string}
     * @memberof FailedAttemptStep
     */
    error: string;
}
/**
 * 
 * @export
 * @interface IdentEidPatchRequest
 */
export interface IdentEidPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    geburtsdatum?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    hausnummer?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    name?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    ort?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    pin?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    plz?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    restrictedId?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    strasse?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentEidPatchRequest
     */
    vorname?: string;
}
/**
 * 
 * @export
 * @interface IdentFscPatchRequest
 */
export interface IdentFscPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    fsc?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    kvnr?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    name?: string;
    /**
     * 
     * @type {string}
     * @memberof IdentFscPatchRequest
     */
    vorname?: string;
}
/**
 * 
 * @export
 * @interface IdentKvnrPatchRequest
 */
export interface IdentKvnrPatchRequest {
    /**
     * 
     * @type {string}
     * @memberof IdentKvnrPatchRequest
     */
    kvnr?: string;
}
/**
 * 
 * @export
 * @interface JourneyDebugStep
 */
export interface JourneyDebugStep {
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    intent: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    journeyId: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    lifecycle: string;
    /**
     * Demo-only: why this journey's current step looks the way it does - either why its tool became the automatic choice, or why a selection among several is being shown at all. Null whenever the step already explains itself (e.g. a Prompt), never part of the production contract.
     * @type {string}
     * @memberof JourneyDebugStep
     */
    note?: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyDebugStep
     */
    stateType: string;
}
/**
 * 
 * @export
 * @interface JourneyLogEntryView
 */
export interface JourneyLogEntryView {
    /**
     * 
     * @type {number}
     * @memberof JourneyLogEntryView
     */
    accountId?: number;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    channelSessionId: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    channelType?: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    createdAt: string;
    /**
     * 
     * @type {{ [key: string]: any; }}
     * @memberof JourneyLogEntryView
     */
    detail: { [key: string]: any; };
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    eventType: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    intent?: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    journeyId?: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    journeyState?: string;
    /**
     * 
     * @type {string}
     * @memberof JourneyLogEntryView
     */
    parentJourneyId?: string;
}
/**
 * 
 * @export
 * @interface JourneyLogResponse
 */
export interface JourneyLogResponse {
    /**
     * 
     * @type {Array<JourneyLogEntryView>}
     * @memberof JourneyLogResponse
     */
    entries: Array<JourneyLogEntryView>;
}
/**
 * Upsert body for the kc-facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3). All fields are optional. accountId is the account Keycloak already knows (sub vorhanden) - the channel is bound to it immediately, once, never overwritten by a later call. targetAcr is Keycloak's requested LoA level, already translated into an orchestrator ACR string, and only raises the channel's floor, never lowers it. amr lists which native Keycloak authenticators (never orchestrator tools) just proved something THIS flow run, one entry per proof - method/loa/factorTypes are resolved server-side from a NativeAuthenticatorDescriptor (see AmrEntry), the kc-facade's own mirror of a ToolDescriptor, not resolved from the orchestrator's own catalog (which stays entirely ignorant of native authenticators). Merged into the channel's evidence and re-checked against the current floor exactly like any other proof; no separate 'combined native acr' field exists, since the orchestrator derives that itself.
 * @export
 * @interface KcChannelUpsertRequest
 */
export interface KcChannelUpsertRequest {
    /**
     * 
     * @type {number}
     * @memberof KcChannelUpsertRequest
     */
    accountId?: number;
    /**
     * 
     * @type {Array<AmrEntry>}
     * @memberof KcChannelUpsertRequest
     */
    amr?: Array<AmrEntry>;
    /**
     * The Web channel's own declaration of which toolIds its Keycloak theme can render (one com.example.dpop.kcext.webtool.WebToolRenderer factory per toolId, registered via META-INF/services) - the kc-facade's counterpart to the App channel's own availableTools (POST /channels). Only read on this channel's first call (a later upsert resumes the already-persisted set); a channel-anonymous caller that omits this gets none of the orchestrator's tools, never all of them.
     * @type {Array<string>}
     * @memberof KcChannelUpsertRequest
     */
    availableTools?: Array<string>;
    /**
     * Only read on this channel's first call, same restriction as availableTools - the kc facade's own, deliberately narrow counterpart to the App facade's `intent` request parameter (docs/05-api.md #"POST /app/channels: intent-Parameter"). Omitted (or null) means kc_select_method, the existing login/step-up behaviour. Only kc_select_method and register are accepted here - unlike the App facade, not every AuthIntent.isEntryIntent value: fast_access/lookup_login assume an APP-shaped channel this facade never has.
     * @type {string}
     * @memberof KcChannelUpsertRequest
     */
    intent?: string;
    /**
     * Required whenever restoreData is present, ignored otherwise. Keycloak's own, durable UserSessionModel id - deliberately NOT read off the peer-auth assertion (the assertion's kc-anchor is always THIS flow run's own channelSessionId, docs/02-domaenenmodell.md Abschnitt 1, so it can't verify a token minted for a DIFFERENT, earlier flow run's channel). Must match what GET .../restore-data was called with to produce this exact restoreData token.
     * @type {string}
     * @memberof KcChannelUpsertRequest
     */
    kcSessionId?: string;
    /**
     * A signed RestoreData token this same UserSession's channel returned earlier via GET .../restore-data, resubmitted verbatim (docs/ideen/web-keycloak-kanal.md #6) - the bulk, one-shot way to seed a brand-new channel with what a PRIOR, unrelated flow run already established, as opposed to accountId/amr above which report what THIS flow run just proved. Both are merged into the channel the same way; only restoreData may already be meaningfully old by the time it arrives here. Opaque to every caller but the orchestrator itself - see RestoreDataCodec.
     * @type {string}
     * @memberof KcChannelUpsertRequest
     */
    restoreData?: string;
    /**
     * 
     * @type {string}
     * @memberof KcChannelUpsertRequest
     */
    targetAcr?: string;
}
/**
 * What the KOBIL SDK needs to activate this device.
 * @export
 * @interface KobilActivationStep
 */
export interface KobilActivationStep {
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    activationCode: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilActivationStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    pin: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    tenantId: string;
    /**
     * 
     * @type {string}
     * @memberof KobilActivationStep
     */
    unlockSecret?: string;
}
/**
 * Waiting for the one-time password the KOBIL SDK produced.
 * @export
 * @interface KobilOtpStep
 */
export interface KobilOtpStep {
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    kobilPin?: string;
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilOtpStep
     */
    missingFields: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof KobilOtpStep
     */
    tenantId: string;
}
/**
 * 
 * @export
 * @interface KobilPinReleaseRequest
 */
export interface KobilPinReleaseRequest {
    /**
     * 
     * @type {KobilPinReleaseRequestUnlock}
     * @memberof KobilPinReleaseRequest
     */
    unlock: KobilPinReleaseRequestUnlock;
}
/**
 * @type KobilPinReleaseRequestUnlock
 * 
 * @export
 */
export type KobilPinReleaseRequestUnlock = BiometricUnlock | PasswordUnlock;
/**
 * 
 * @export
 * @interface KobilUnlockCredential
 */
export interface KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockCredential
     */
    kind: string;
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockCredential
     */
    userVerification?: KobilUnlockCredentialUserVerificationEnum;
}


/**
 * @export
 */
export const KobilUnlockCredentialUserVerificationEnum = {
    PIN: 'PIN',
    BIOMETRIC: 'BIOMETRIC'
} as const;
export type KobilUnlockCredentialUserVerificationEnum = typeof KobilUnlockCredentialUserVerificationEnum[keyof typeof KobilUnlockCredentialUserVerificationEnum];

/**
 * The app must unlock the backend-held PIN; these are the accepted ways.
 * @export
 * @interface KobilUnlockStep
 */
export interface KobilUnlockStep {
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockStep
     */
    kobilUserId: string;
    /**
     * 
     * @type {string}
     * @memberof KobilUnlockStep
     */
    tenantId: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof KobilUnlockStep
     */
    unlockOptions: Array<string>;
}
/**
 * A single candidate was auto-activated; this explains why the step appears.
 * @export
 * @interface MessageStep
 */
export interface MessageStep {
    /**
     * 
     * @type {string}
     * @memberof MessageStep
     */
    message: string;
}
/**
 * The account's active authentication methods (docs/05-api.md #2). Never contains fsc.
 * @export
 * @interface MethodsResponse
 */
export interface MethodsResponse {
    /**
     * 
     * @type {Array<ActiveMethodView>}
     * @memberof MethodsResponse
     */
    methods: Array<ActiveMethodView>;
}
/**
 * 
 * @export
 * @interface MgmtPasswordSetRequest
 */
export interface MgmtPasswordSetRequest {
    /**
     * 
     * @type {string}
     * @memberof MgmtPasswordSetRequest
     */
    newPassword?: string;
}
/**
 * 
 * @export
 * @interface MgmtPasswordVerifyRequest
 */
export interface MgmtPasswordVerifyRequest {
    /**
     * 
     * @type {string}
     * @memberof MgmtPasswordVerifyRequest
     */
    password?: string;
}
/**
 * 
 * @export
 * @interface MgmtPasswordVerifyResponse
 */
export interface MgmtPasswordVerifyResponse {
    /**
     * 
     * @type {boolean}
     * @memberof MgmtPasswordVerifyResponse
     */
    valid: boolean;
}
/**
 * Which inputs this step is still waiting for.
 * @export
 * @interface MissingFields
 */
export interface MissingFields {
    /**
     * 
     * @type {Array<string>}
     * @memberof MissingFields
     */
    missingFields: Array<string>;
}
/**
 * 
 * @export
 * @interface Next
 */
export interface Next {
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    context?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    step: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    toolId?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    toolSessionId?: string;
    /**
     * 
     * @type {string}
     * @memberof Next
     */
    type: string;
}
/**
 * 
 * @export
 * @interface PasswordUnlock
 */
export interface PasswordUnlock extends KobilUnlockCredential {
    /**
     * 
     * @type {string}
     * @memberof PasswordUnlock
     */
    password: string;
}


/**
 * 
 * @export
 * @interface Prompt
 */
export interface Prompt {
    /**
     * 
     * @type {string}
     * @memberof Prompt
     */
    description?: string;
    /**
     * 
     * @type {string}
     * @memberof Prompt
     */
    kind: string;
    /**
     * 
     * @type {string}
     * @memberof Prompt
     */
    title?: string;
}
/**
 * A QR pairing in progress: the pairing code, and the verification code once known.
 * @export
 * @interface QrPairingStep
 */
export interface QrPairingStep {
    /**
     * 
     * @type {string}
     * @memberof QrPairingStep
     */
    pairingCode?: string;
    /**
     * 
     * @type {string}
     * @memberof QrPairingStep
     */
    verificationCode?: string;
}
/**
 * The channel's current RestoreData, signed - null if there is nothing worth restoring yet.
 * @export
 * @interface RestoreDataResponse
 */
export interface RestoreDataResponse {
    /**
     * 
     * @type {string}
     * @memberof RestoreDataResponse
     */
    restoreData?: string;
}
/**
 * Several procedures are possible; the client shows a selection.
 * @export
 * @interface SelectMethodStep
 */
export interface SelectMethodStep {
    /**
     * 
     * @type {string}
     * @memberof SelectMethodStep
     */
    description?: string;
    /**
     * 
     * @type {Array<string>}
     * @memberof SelectMethodStep
     */
    options: Array<string>;
    /**
     * 
     * @type {string}
     * @memberof SelectMethodStep
     */
    title?: string;
}
/**
 * @type StepData
 * What the current step needs to render. `kind` names the shape; see the mapping on this schema for the ones this deployment can produce.
 * @export
 */
export type StepData = { kind: 'confirm' } & ConfirmStep | { kind: 'failed-attempt' } & FailedAttemptStep | { kind: 'kobil-activation' } & KobilActivationStep | { kind: 'kobil-otp' } & KobilOtpStep | { kind: 'kobil-unlock' } & KobilUnlockStep | { kind: 'message' } & MessageStep | { kind: 'missing-fields' } & MissingFields | { kind: 'qr-pairing' } & QrPairingStep | { kind: 'select-method' } & SelectMethodStep;
/**
 * Mock Keycloak AccessToken (a spec-shaped unsecured JWT, alg=none - parse and display its payload, no verification needed) plus both token lifetimes. The RefreshToken value itself is deliberately never part of this response - it's a credential and stays server-side; refreshExpiresAt is the only thing about it exposed.
 * @export
 * @interface TokenResponse
 */
export interface TokenResponse {
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    accessExpiresAt: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    accessToken: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    refreshExpiresAt: string;
    /**
     * 
     * @type {string}
     * @memberof TokenResponse
     */
    tokenType?: string;
}
/**
 * 
 * @export
 * @interface ToolCatalogEntry
 */
export interface ToolCatalogEntry {
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    method: string;
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    role: string;
    /**
     * 
     * @type {string}
     * @memberof ToolCatalogEntry
     */
    toolId: string;
}
