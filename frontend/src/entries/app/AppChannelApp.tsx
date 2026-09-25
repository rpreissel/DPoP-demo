import { resolveText, t } from '../../texts'
import { Tx } from '../../Tx'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { computeJwkThumbprint, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from '../../dpop.ts'
import '../../App.css'
import '../../phone.css'
import type { ActiveMethodView, ChannelResponse, DemoInfo, DeviceLinkResponse, Next, StepData } from '../../types'
import { confirmPromptOf, stepDataOf } from '../../types'
import { getUIComponent } from '../../routing.ts'
import { explainToolStep, knownToolIds, metaFor, renderToolStep } from '../../tools/registry'
import type { ToolRenderContext } from '../../tools/types'
import {
  abandonTool,
  activateTool,
  backFromTool,
  answerPrompt,
  cancelJourney,
  createChannel,
  deactivateMethod,
  describeError,
  getChannel,
  getDeviceLink,
  getTool,
  startLogout,
  onApiCall,
  raiseRequiredAcr,
  startAccountDeletion,
  startManageMethods,
  startPeerLogin,
} from '../../api.ts'
import {
  forgetChannelSessionId,
  forgetPendingPairingCode,
  loadAvailableTools,
  loadChannelSessionId,
  loadPendingPairingCode,
  storeAvailableTools,
  storeChannelSessionId,
  storePendingPairingCode,
} from '../../session.ts'
import { storeReturnedNectCase } from '../../tools/nect/returnedCase'
import { dropStaleKobilData } from '../../tools/kobil/localData'
import { shorten } from '../../format.ts'
import { ChannelNav } from '../../components/ChannelNav'
import { Demo, DemoArea, DemoProvider } from '../../components/DemoArea'
import { PhoneFrame } from '../../components/PhoneFrame'
import { AuthenticationCompletedView, type AccountView } from '../../components/AuthenticationCompletedView'
import { StepExplanation } from '../../components/StepExplanation'
import { SessionSummary } from '../../components/SessionSummary'
import { ButtonDiagrams } from '../../components/ButtonDiagrams'
import { DebugSidebar, type DebugEvent } from '../../components/DebugSidebar'
import { SelectMethodView } from '../../components/SelectMethodView'
import { JourneyStructureView } from '../../components/JourneyStructureView'
import { PromptView } from '../../components/PromptView'
import { ToolAvailabilitySelector } from '../../components/ToolAvailabilitySelector'
import { UnavailableTools } from '../../components/UnavailableTools'
import { Disclosure } from '../../components/Disclosure'
import { DeviceIdentityCard } from '../../components/DeviceIdentityCard'
import { DiagramHint } from '../../components/DiagramHint'
import { CURRENT_STEP_BY_STATE_TYPE, currentJourneyDiagramKey, journeyContextLabel, JOURNEY_DIAGRAMS } from '../../journeyDiagrams'

interface ActiveTool {
  toolSessionId: string
  toolId: string
}


/**
 * Wire vocabulary of AuthIntent's entry intents (backend `AuthIntent.fromRequest`, case-
 * insensitive) that this client-side entry point can act on, mapped to `handleStart`'s own mode
 * names. Kept deliberately small - only intents this app can actually enter cold from a URL.
 */
const INTENT_TO_START_MODE: Record<string, 'auto' | 'login' | 'register' | 'confirmPeerLogin'> = {
  fast_access: 'auto',
  lookup_login: 'login',
  register: 'register',
  confirm_peer_login: 'confirmPeerLogin',
}

export function AppChannelApp() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [jwkThumbprint, setJwkThumbprint] = useState<string | undefined>()
  // Whose device this is (DeviceAccountLink), shown on DeviceIdentityCard (FE-14) even before any
  // channel exists - null means "not asked yet / still loading", distinct from an answered "not
  // linked" (DeviceLinkResponse.linked === false).
  const [deviceLink, setDeviceLink] = useState<DeviceLinkResponse | null>(null)
  const [channelSessionId, setChannelSessionId] = useState<string | undefined>()
  const [channelState, setChannelState] = useState<string | undefined>()
  const [currentAcr, setCurrentAcr] = useState<string | undefined>()
  const [currentAmr, setCurrentAmr] = useState<string[] | undefined>()
  const [activeMethods, setActiveMethods] = useState<ActiveMethodView[] | undefined>()
  const [next, setNext] = useState<Next | undefined>()
  const [stepData, setStepData] = useState<StepData | undefined>()
  // The orchestrator's `message` step, kept across auto-activating the one tool it announces -
  // that tool's own response replaces stepData and would otherwise swallow it.
  const [carriedMessage, setCarriedMessage] = useState<string | undefined>()
  const [demo, setDemo] = useState<DemoInfo | undefined>()
  const [activeTool, setActiveTool] = useState<ActiveTool | null>(null)
  // Set from the WEB channel's demo link (?pairingCode=..., docs/07-betrieb.md #5) -
  // the Keycloak-side QR page's demo link points straight at this app's own /app/ entry, so opening
  // it lands here directly instead of a fictitious native deep-link scheme. Only captured/surfaced
  // for now (shown as a banner near the entry choice) besides driving the auto-start effect below;
  // actually submitting it as confirm-qr-login's own `pairingCode` field is bmh.4/bmh.6.
  const [pendingPairingCode, setPendingPairingCode] = useState<string | undefined>()
  // Gates "Zurück" - NOT "did another candidate really exist" (abandoning is always a
  // safe, backend-handled fallback regardless), only "would showing the button be worth it right
  // now". >0 whenever activeTool is set: handleSelectMethod after an explicit multi-candidate
  // choice (the real remaining count), the auto-activate effect for a direct single-candidate skip
  // and for a resumed tool session alike (both set 1) - a resumed step with nothing to switch to
  // would otherwise leave an already-AUTHENTICATED channel's step-up with zero way out at all
  // ("Zur Startseite" doesn't render while inToolMode, and AuthenticationCompletedView - the only
  // place "Abmelden" lives - doesn't render then either).
  const [alternativesCount, setAlternativesCount] = useState(0)
  // Rückfrage vor dem Verwerfen einer Registrierung: Abbrechen beendet nicht nur den Schritt,
  // sondern die Journey - der Kanal fällt auf ANONYMOUS zurück und das vorläufige Konto wird
  // gelöscht (JourneyService.fallBack -> deleteIfAbandonedUnidentified), mitsamt einer eID-
  // Bezeugung, die schon darin steckt. Das darf nicht ein Klick nebenbei sein.
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  // Whether this session has already proven something (backend ChannelBlock.hasProvenFactor) -
  // only then does leaving a registration throw anything away, and only then is it worth asking.
  const [hasProvenFactor, setHasProvenFactor] = useState(false)
  // "Dieses Gerät zurücksetzen" on the start screen asks once - it forgets this device's key.
  const [confirmingReset, setConfirmingReset] = useState(false)
  const [error, setError] = useState('')
  // The logged-in screen (welcome, profile, security) - kept here so a step-up or an added method
  // returns to where it was started; a new channel starts at the welcome again.
  const [accountView, setAccountView] = useState<AccountView>('home')
  useEffect(() => setAccountView('home'), [channelSessionId])
  // Only takes effect on the next channel-creating action (Verbinden/Login ohne DPoP/Registrieren
  // below) - needed to reach enroll-password at all: it requires a confirmed email first, but a
  // single loa1 method already satisfies the default floor and ends registration before password
  // could ever be offered - only requesting loa2 up front keeps the flow going long enough to
  // chain sms/email -> password.
  const [requiredAcr, setRequiredAcr] = useState('')
  // Client capability declaration (docs/03-tool-architektur.md, availability) - starts as
  // "everything this client can render" and is only narrowed by unchecking in the demo selector.
  // Remembered in localStorage (session.ts) so the choice survives a reload.
  const [availableTools, setAvailableToolsState] = useState<string[]>(() => loadAvailableTools() ?? knownToolIds)

  function setAvailableTools(toolIds: string[]) {
    setAvailableToolsState(toolIds)
    storeAvailableTools(toolIds)
  }
  const [debugOpen, setDebugOpen] = useState(false)
  const [debugLog, setDebugLog] = useState<DebugEvent[]>([])
  const debugIdRef = useRef(0)
  // Drives the "Sitzung fortsetzen" button's visibility on the "no channel" screen - kept in
  // sync explicitly (not derived from channelSessionId) since it must survive Clear/Logout
  // clearing the in-memory state while still reflecting localStorage accurately afterwards.
  const [rememberedChannelSessionId, setRememberedChannelSessionId] = useState(() => loadChannelSessionId())

  function logEvent(label: string, extra?: { request?: unknown; response?: unknown; error?: string }) {
    debugIdRef.current += 1
    setDebugLog((prev) => [{ id: debugIdRef.current, time: new Date().toLocaleTimeString(), label, ...extra }, ...prev].slice(0, 200))
  }

  // Single source of truth for the debug log's API entries: every call() in api.ts reports here,
  // so individual handlers below don't each hand-write their own (drift-prone) log entry anymore.
  useEffect(() => {
    return onApiCall((entry) => {
      logEvent(`${entry.method} ${entry.path}`, { request: entry.requestBody, response: entry.responseBody, error: entry.error })
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function clearChannelState() {
    setChannelSessionId(undefined)
    setChannelState(undefined)
    setHasProvenFactor(false)
    setCurrentAcr(undefined)
    setCurrentAmr(undefined)
    setActiveMethods(undefined)
    setNext(undefined)
    setStepData(undefined)
    setDemo(undefined)
    setActiveTool(null)
    setAlternativesCount(0)
  }

  /**
   * The one apply-function for every endpoint's response (docs/05-api.md #2: unified envelope) -
   * channel-level and tool-level alike carry the same `{channel, next, stepData, demo}` shape, so
   * there's no more separate tool-response path. `currentAcr`/`currentAmr`/`activeMethods` are
   * the one exception: tool responses never carry them (not core flow data, only the security-
   * summary screen reads them) - the effect below fetches them on demand exactly when that screen
   * is reached, the same way any real screen would load its own data rather than have every
   * response carry it "just in case".
   *
   * [actedToolId], when given, is the tool that was just acted on. `next.toolSessionId` (set
   * whenever a ToolSession exists for the due step) tells us whether that's still the active
   * tool or the response already handed off to a different, not-yet-activated one (e.g.
   * ident-fsc -> auth-sms on single-candidate skip) - pairing the wrong toolId with a session
   * would make the auto-activate effect below think the new tool is already active and never
   * actually activate it (no request ever fires, no TAN issued).
   */
  function applyResponse(response: ChannelResponse, actedToolId?: string) {
    // Every response that got this far is a successful one, so whatever went wrong before is
    // over - without this the banner of a rejected step (e.g. an address that belonged to
    // somebody else) stayed on screen through every following step of the run.
    setError('')
    // Eine offene Verwerfen-Rückfrage gehört zu dem Schritt, auf dem sie gestellt wurde.
    setConfirmingDiscard(false)
    setChannelSessionId(response.channel.channelSessionId)
    storeChannelSessionId(response.channel.channelSessionId)
    setRememberedChannelSessionId(response.channel.channelSessionId)
    setChannelState(response.channel.state)
    setHasProvenFactor(response.channel.hasProvenFactor ?? false)
    setCurrentAcr(response.channel.currentAcr)
    setCurrentAmr(response.channel.currentAmr)
    setActiveMethods(response.channel.activeMethods)
    setNext(response.next)
    setStepData(response.stepData)
    setCarriedMessage(undefined)
    setDemo(response.demo)

    const next = response.next
    if (actedToolId && next?.type === 'tool' && next.toolId === actedToolId && next.toolSessionId) {
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: actedToolId })
    } else {
      setActiveTool(null)
    }
  }

  // Bootstrap: ONLY the DPoP key pair (and its thumbprint) - nothing channel-related happens
  // automatically. The app must remember its own channelSessionId (docs/02-domaenenmodell.md #3)
  // and the user explicitly chooses how to start below (resume/connect/login/register); the DPoP
  // key alone only proves which device this is, it is never a lookup key for resuming a session.
  useEffect(() => {
    let active = true
    async function init() {
      const keyPair = await getOrCreateDpopKeyPair()
      if (!active) return
      setDpop(keyPair)
      const thumbprint = await computeJwkThumbprint(keyPair.publicJwk)
      if (!active) return
      setJwkThumbprint(thumbprint)
      logEvent('DPoP-Key geladen/erzeugt', { response: { jwkThumbprint: thumbprint, publicJwk: keyPair.publicJwk } })
    }
    init().catch((err) => setError(describeError(t('Start der App fehlgeschlagen'), err)))
    return () => {
      active = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Refetches whenever the entry screen is showing (no channel) and a key is ready - covers the
  // cases that actually change it: first mount, "Zur Startseite"/Logout-then-clear, and a
  // recreated key (handleRecreateKey clears the channel too, landing back here). Deliberately not
  // fetched while a channel is active - DeviceIdentityCard just keeps showing its last answer then.
  useEffect(() => {
    if (!dpop || channelSessionId) return
    let active = true
    getDeviceLink(dpop)
      .then((link) => {
        if (!active) return
        setDeviceLink(link)
        // Local data whose binding is gone has to go with it - the rule itself belongs to the
        // owning tool module, not here.
        dropStaleKobilData(link.boundCredentials)
      })
      .catch(() => {
        // Non-fatal - DeviceIdentityCard just shows "…" a bit longer, no error banner for this.
      })
    return () => {
      active = false
    }
  }, [dpop, channelSessionId])

  /**
   * URL entry point (docs/10-frontend.md #1, #QR): reads `intent` (AuthIntent's own wire
   * vocabulary, same as `createChannel`'s `intent` body field) and `pairingCode` from the query
   * string once `dpop` is ready, then strips both from the URL. Guarded by a ref against
   * StrictMode's double effect-invocation (same pattern as `activatingToolIdRef` below).
   *
   * `confirm_peer_login` gets one extra step before falling back to a fresh channel: if this
   * device already remembers a channel, it is loaded first - if that turns out to be
   * AUTHENTICATED already, `handlePeerLogin` runs on THAT channel instead of discarding it via a
   * brand-new `createChannel` call (which would force a full re-login from scratch). Which proof
   * (if any) that actually requires is entirely the server's call (see `ConfirmPeerLoginStrategy`,
   * docs/04-orchestrierung.md CONFIRM_PEER_LOGIN) - the client only decides WHICH channel
   * to act on, never how much reauth it costs.
   */
  const urlEntryHandledRef = useRef(false)
  useEffect(() => {
    if (!dpop || urlEntryHandledRef.current) return
    const params = new URLSearchParams(window.location.search)
    const intentParam = params.get('intent')
    const pairingCode = params.get('pairingCode')
    const nectCaseId = params.get('nectCaseId')
    if (!intentParam && !pairingCode && !nectCaseId) return
    urlEntryHandledRef.current = true

    if (pairingCode) {
      setPendingPairingCode(pairingCode)
      storePendingPairingCode(pairingCode)
      logEvent('QR-Pairing-Code aus Link übernommen', { response: { pairingCode } })
    }

    params.delete('intent')
    params.delete('pairingCode')
    params.delete('nectCaseId')
    const query = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (query ? `?${query}` : '') + window.location.hash)

    // Back from Nect's jump page (ident-nect): the channel waiting for this case is the one this
    // device remembers - resume it, and the tool's redirect step reports the case itself.
    if (nectCaseId) {
      storeReturnedNectCase(nectCaseId)
      logEvent('Rücksprung von Nect', { response: { nectCaseId } })
      handleStart('resume')
      return
    }

    const mode = intentParam ? INTENT_TO_START_MODE[intentParam.toLowerCase()] : undefined
    if (!mode) return

    if (mode !== 'confirmPeerLogin') {
      handleStart(mode)
      return
    }

    const rememberedId = loadChannelSessionId()
    if (!rememberedId) {
      handleStart('confirmPeerLogin')
      return
    }
    getChannel(dpop, rememberedId)
      .then((response) => {
        if (response.channel.state !== 'AUTHENTICATED') {
          forgetChannelSessionId()
          setRememberedChannelSessionId(null)
          return handleStart('confirmPeerLogin')
        }
        applyResponse(response)
        // Not handlePeerLogin() - that closure was captured when this effect first ran (still
        // seeing channelSessionId as undefined, since applyResponse's setState above hasn't
        // committed yet) and would bail out on its own `!channelSessionId` guard. Acting directly
        // on the id just resolved sidesteps the stale-closure trap entirely. A failure here (e.g.
        // the loa2 gate) must NOT fall through to the outer catch below - that would discard the
        // just-applied authenticated channel and start over from scratch - so it's reported via
        // the normal error path instead.
        return startPeerLogin(dpop, response.channel.channelSessionId)
          .then((peerResponse) => applyResponse(peerResponse))
          .catch((err) => setError(describeError(t('Web-Login-Bestätigung fehlgeschlagen'), err)))
      })
      .catch(() => {
        forgetChannelSessionId()
        setRememberedChannelSessionId(null)
        return handleStart('confirmPeerLogin')
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop])

  /**
   * Back-button support, scoped to "leave the running process, land on the start choice" (docs/
   * 10-frontend.md #1) - not a step-by-step undo, the server-driven `next` chain is forward-only.
   * `channelActiveRef` mirrors `channelSessionId` (a ref, not state, so the popstate listener
   * below always reads the current value instead of the one captured at registration time).
   */
  const channelActiveRef = useRef(false)
  useEffect(() => {
    const wasActive = channelActiveRef.current
    channelActiveRef.current = !!channelSessionId
    if (!wasActive && channelSessionId) {
      window.history.pushState({ dpopDemoChannel: true }, '', window.location.href)
    }
  }, [channelSessionId])

  useEffect(() => {
    function onPopState(event: PopStateEvent) {
      const state = event.state as { dpopDemoChannel?: boolean } | null
      if (!state?.dpopDemoChannel && channelActiveRef.current) {
        handleClearChannel()
      }
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Auto-activate whenever `next` points at a tool we haven't activated yet.
  // activatingToolIdRef guards against StrictMode's double effect-invocation in dev: refs update
  // synchronously (unlike state), so the second invocation sees the first one's in-flight marker
  // before it can fire a second POST for the same toolId.
  const activatingToolIdRef = useRef<string | null>(null)
  useEffect(() => {
    if (!dpop || !channelSessionId || !next || next.type !== 'tool' || !next.toolId) return
    if (activeTool?.toolId === next.toolId) return
    if (activatingToolIdRef.current === next.toolId) return

    // A resumed process already has a running ToolSession for this step (docs/05-api.md #2:
    // next.toolSessionId) - activating again would start a NEW attempt from scratch (e.g. a
    // second TAN for enroll-sms), discarding whatever was already entered.
    if (next.toolSessionId) {
      // Resuming (e.g. after a reload) - whether alternatives existed originally is lost, but
      // that's not what this count actually gates: abandoning is always a safe, backend-handled
      // fallback (see the auto-activate branch below), so hiding "Zurück" here isn't a
      // conservative default, it's a dead end - on an already-AUTHENTICATED channel (e.g.
      // CONFIRM_PEER_LOGIN's step-up) neither "Zur Startseite" nor AuthenticationCompletedView's
      // "Abmelden" render while inToolMode, leaving zero way out. Same 1-not-0 treatment as a
      // fresh single-candidate auto-activation.
      setAlternativesCount(1)
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: next.toolId })
      // The channel-level GET that got us here only reports a bare pointer (JourneyService.stepFor
      // returns no stepData once a tool is active) - missingFields, demo hints (e.g. the
      // "Demo-Passwort" prefill) all came from the tool's OWN activation/patch response and are
      // otherwise lost on resume. Every tool controller exposes exactly this read-back
      // (ToolControllerSupport.buildReadResponse, GET .../tools/{id}/{toolId}) - fetch it now.
      const toolId = next.toolId
      const toolSessionId = next.toolSessionId
      activatingToolIdRef.current = toolId
      getTool(dpop, toolSessionId, toolId)
        .then((response) => applyResponse(response, toolId))
        .catch((err) => setError(describeError(t('Stand des Verfahrens konnte nicht geladen werden'), err)))
        .finally(() => {
          if (activatingToolIdRef.current === toolId) activatingToolIdRef.current = null
        })
      return
    }

    const toolId = next.toolId
    // A single candidate offered directly (no selection screen, e.g. FastAccessState.PreferredAuth
    // suggesting the linked device) is NOT the same as "no alternative exists" - JourneyService
    // only skips the selection screen because there is exactly one OFFERED candidate right now;
    // abandoning is still a normal, backend-handled fallback for every such state (every intent's
    // JourneyEvent.Abandoned handler resolves cleanly, worst case Decision.Cancel - never an
    // error). So "Zurück" stays offered here, same as after an explicit selection.
    setAlternativesCount(1)
    activatingToolIdRef.current = toolId
    const pendingText = stepDataOf(stepData, 'message')?.message
    const pendingMessage = pendingText ? resolveText(pendingText) : undefined
    activateTool(dpop, channelSessionId, toolId, activationBodyFor(toolId))
      .then((response) => {
        // Preserve the journey-level context message (e.g. "E-Mail-Bestätigung ausstehend")
        // across auto-activation: the tool endpoint's own response carries the tool's step, so
        // the one from the offering state would otherwise be lost. Kept beside stepData, not
        // merged into it - a message glued onto another step's shape would be neither of them.
        applyResponse(response, toolId)
        setCarriedMessage(pendingMessage)
      })
      .catch((err) => setError(describeError(t('Verfahren konnte nicht gestartet werden'), err)))
      .finally(() => {
        if (activatingToolIdRef.current === toolId) activatingToolIdRef.current = null
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, activeTool])

  /**
   * Fetches currentAcr/currentAmr/activeMethods on demand when the security-summary screen
   * (`AuthenticationCompletedView`) is actually reached - tool responses never carry them
   * (docs/05-api.md #2), only the real channel resource does. `currentAcr === undefined` is a
   * reliable trigger: applyResponse always clears it on every tool response (whether or not that
   * response settled `next` into authenticated), so it's only left set once this effect has
   * already backfilled it for the CURRENT authenticated state - a later re-authentication (e.g.
   * after a step-up) clears it again via applyResponse first, firing this effect anew.
   *
   * loadingSecurityDetailsRef guards against StrictMode's double effect-invocation in dev, same
   * reasoning as activatingToolIdRef above: without it, the second invocation fires its own
   * concurrent getChannel() before the first has set currentAcr, and the loser can come back as a
   * CONCURRENT_MODIFICATION error instead of just being a wasted duplicate read.
   */
  const loadingSecurityDetailsRef = useRef(false)
  useEffect(() => {
    if (!dpop || !channelSessionId) return
    if (next?.type !== 'orchestrator' || next.context !== 'authentication' || next.step !== 'authenticated') return
    if (currentAcr !== undefined) return
    if (loadingSecurityDetailsRef.current) return
    loadingSecurityDetailsRef.current = true

    getChannel(dpop, channelSessionId)
      .then((response) => {
        setCurrentAcr(response.channel.currentAcr)
        setCurrentAmr(response.channel.currentAmr)
        setActiveMethods(response.channel.activeMethods)
      })
      .catch((err) => setError(describeError(t('Sicherheitsdetails laden fehlgeschlagen'), err)))
      .finally(() => {
        loadingSecurityDetailsRef.current = false
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, currentAcr])

  /**
   * Every explicit way a channel comes into existence (docs/04-orchestrierung.md, lookup-based
   * login): "resume" reads a remembered channelSessionId (GET), the rest each mint a brand-new
   * channel with the corresponding `intent` (the backend's AuthIntent name (AuthIntent.fromRequest),
   * "auto" omits it (today's default: DeviceAccountLink found -> LOGIN, else REGISTRATION).
   */
  async function handleStart(mode: 'resume' | 'auto' | 'login' | 'register' | 'confirmPeerLogin') {
    if (!dpop) return
    try {
      setError('')
      if (mode === 'resume') {
        const rememberedId = loadChannelSessionId()
        if (!rememberedId) return
        try {
          const response = await getChannel(dpop, rememberedId)
          applyResponse(response)
        } catch (err) {
          forgetChannelSessionId()
          setRememberedChannelSessionId(null)
          throw err
        }
        return
      }
      const intent =
        mode === 'auto' ? undefined : mode === 'login' ? 'lookup_login' : mode === 'confirmPeerLogin' ? 'confirm_peer_login' : mode
      const response = await createChannel(dpop, requiredAcr || undefined, intent, availableTools)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Start fehlgeschlagen'), err))
    }
  }

  /** "Abbrechen" on the scanned-code screen: the code is dropped, the normal start screen returns. */
  function handleForgetPairingCode() {
    forgetPendingPairingCode()
    setPendingPairingCode(undefined)
    setError('')
  }

  /** Local-only: forgets the remembered channelSessionId and resets all channel state - no backend call, unlike Logout. */
  function handleClearChannel() {
    forgetChannelSessionId()
    setRememberedChannelSessionId(null)
    clearChannelState()
    setError('')
    logEvent('Kanal lokal geleert (kein Backend-Aufruf)')
  }

  /** Forgets this device's identity entirely: deletes the DPoP key, generates a new one. Does NOT create a channel - same "nothing happens automatically" principle as startup. */
  async function handleRecreateKey() {
    try {
      setError('')
      forgetChannelSessionId()
      setRememberedChannelSessionId(null)
      clearChannelState()
      setDeviceLink(null)
      await resetDpopKeyPair()
      const keyPair = await getOrCreateDpopKeyPair()
      setDpop(keyPair)
      const thumbprint = await computeJwkThumbprint(keyPair.publicJwk)
      setJwkThumbprint(thumbprint)
      logEvent('DPoP-Key neu erzeugt', { response: { jwkThumbprint: thumbprint, publicJwk: keyPair.publicJwk } })
    } catch (err) {
      setError(describeError(t('Key-Neuerzeugung fehlgeschlagen'), err))
    }
  }

  /** Keeps the DPoP key (same device) but ends this session on the backend. Does NOT auto-start a new one - the user picks explicitly, same as on first load. */
  async function handleLogout() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startLogout(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Abmelden fehlgeschlagen'), err))
    }
  }

  /**
   * Raises this channel's required level and, if the current evidence doesn't already satisfy
   * it, moves the channel to STEP_UP_IN_PROGRESS - the response's `next` then points at a
   * candidate AUTH tool (or a selection page), rendered by the very same tool forms/routing
   * already used for LOGIN, no separate step-up UI needed. A 410 (target level unreachable with
   * the account's enrolled methods) surfaces via the normal error path.
   */
  async function handleStepUp(requiredAcr: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await raiseRequiredAcr(dpop, channelSessionId, requiredAcr)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Step-up fehlgeschlagen'), err))
    }
  }

  /**
   * Answers whatever AnswerableState/Prompt the current step is waiting on instead of a tool run
   * (device-binding offer, account-deletion confirmation, ...). Both answers continue the journey -
   * declining is a valid outcome, not a cancel.
   */
  async function handleAnswer(accept: boolean) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await answerPrompt(dpop, channelSessionId, accept)
      if (response.channel.state === 'LOGGED_OUT') {
        // Signed out means back to the start screen - a page only saying so is one tap too many.
        handleClearChannel()
      } else {
        applyResponse(response)
      }
    } catch (err) {
      setError(describeError(t('Antwort fehlgeschlagen'), err))
    }
  }

  /** Voluntary enrollment on an already-AUTHENTICATED channel - offers a new enroll-* tool, which the auto-activate effect below then picks up. */
  async function handleAddMethod() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startManageMethods(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Hinzufügen fehlgeschlagen'), err))
    }
  }

  /** Confirm a WEB-channel QR login from this already-authenticated channel - gates on loa2 (offers step-up first if needed), then confirm-qr-login, same as the cold-entry 'confirmPeerLogin' start choice. */
  async function handlePeerLogin() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startPeerLogin(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Web-Login-Bestätigung fehlgeschlagen'), err))
    }
  }

  async function handleDeactivateMethod(methodInstanceId: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await deactivateMethod(dpop, channelSessionId, methodInstanceId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Deaktivieren fehlgeschlagen'), err))
    }
  }

  /** Starts the account-deletion journey - the confirmation prompt and the re-authentication step that follow render themselves via the normal next/stepData flow. */
  async function handleDeleteAccount() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startAccountDeletion(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Konto löschen fehlgeschlagen'), err))
    }
  }

  /**
   * Declines the running tool - the way out a tool offers itself (ToolRenderContext.onSkip, e.g.
   * ident-kvnr's "jetzt nicht"). On a fallback state the chain moves on; on a mandatory one the full
   * choice comes back. The sticky bar's "Zurück" is [handleBack] instead: back to the selection
   * without declining anything. Abbrechen ends the whole journey.
   */
  async function handleAbandonTool() {
    if (!dpop || !activeTool) return
    try {
      setError('')
      const response = await abandonTool(dpop, activeTool.toolSessionId, activeTool.toolId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Wechsel fehlgeschlagen'), err))
    }
  }

  /** "Zurück": back to the selection, the running tool still on it - nothing is declined. */
  async function handleBack() {
    if (!dpop || !activeTool) return
    try {
      setError('')
      const response = await backFromTool(dpop, activeTool.toolSessionId, activeTool.toolId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Zurück fehlgeschlagen'), err))
    }
  }

  /** Nothing proven yet, so nothing to lose: end the journey and go back to the start screen. */
  async function handleLeaveToStart() {
    await handleCancel()
    handleClearChannel()
  }

  async function handleCancel() {
    if (!dpop || !channelSessionId) return
    try {
      const response = await cancelJourney(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Abbrechen fehlgeschlagen'), err))
    }
  }

  /**
   * confirm-qr-login is the only tool whose activation body carries anything (docs/03-tool-
   * architektur.md, per-module own API - this stays a one-off special case here, not a generic
   * dispatch): when a pairing code is already known (scanned/deep-linked before the tool was even
   * chosen), send it straight in the activation POST so the backend can skip the `input` step
   * entirely (ConfirmQrLoginToolController) instead of the app showing a screen with nothing left
   * for the user to type. Consumed here, not just left for the input step's own pre-fill.
   */
  function activationBodyFor(toolId: string): Record<string, unknown> | undefined {
    if (toolId !== 'confirm-qr-login') return undefined
    const pairingCode = loadPendingPairingCode()
    if (!pairingCode) return undefined
    forgetPendingPairingCode()
    setPendingPairingCode(undefined)
    return { pairingCode }
  }

  async function handleSelectMethod(toolId: string) {
    if (!dpop || !channelSessionId) return
    try {
      // The options just shown minus the one being picked = how many real alternatives remain.
      setAlternativesCount(Math.max(0, (stepDataOf(stepData, 'select-method')?.options.length ?? 1) - 1))
      const response = await activateTool(dpop, channelSessionId, toolId, activationBodyFor(toolId))
      applyResponse(response, toolId)
    } catch (err) {
      setError(describeError(t('Verfahren konnte nicht gestartet werden'), err))
    }
  }

  const uiComponent = getUIComponent(next)
  // Nothing to cancel before a process even started, or once it's already finished.
  const canCancel = !!next && !(next.type === 'orchestrator' && next.context === 'authentication' && next.step === 'authenticated')
  // Once a tool is actively awaiting input - or the user is choosing WHICH tool, i.e.
  // select-method - every other action (bail into a different flow, logout, ...) just competes
  // for attention with the one that matters: Abbrechen.
  const inToolMode = !!activeTool || uiComponent === 'select-method'
  // What the running journey chain is actually FOR right now (its innermost/active entry) - shown
  // as a one-line context banner once past the Startseite, so the current step doesn't stand there
  // context-free (e.g. "Verfahren wählen" alone doesn't say for what). Undefined once nothing is
  // running (e.g. idle AUTHENTICATED with no journey), same as journeys being empty - no banner then.
  const journeyContextKey = currentJourneyDiagramKey(demo?.journeys)
  // Nur die Registrierung baut etwas auf, das ein Abbruch wegwirft (das vorläufige Konto samt
  // Bezeugung). Ein Step-Up oder eine Bestätigung auf einem bestehenden Konto lässt nichts
  // zurück, dort bleibt es beim schlichten "Abbrechen".
  const discardsRegistration =
    (journeyContextKey === 'register' || journeyContextKey === 'registerEnrollFirst') && channelState !== 'AUTHENTICATED'
  // Same box the innermost journey's own hint highlights in JourneyStructureView (JourneyDebugStep.stateType) - the context banner's diagram popover marks it too, not just the shape.
  const journeyContextCurrentStep = journeyContextKey ? CURRENT_STEP_BY_STATE_TYPE[journeyContextKey]?.[demo?.journeys?.at(-1)?.stateType ?? ''] : undefined

  // Everything a tool's own render() needs (src/tools/registry.ts) - assembled once here from
  // `next`/`activeTool`, each tool module then calls its own api.ts and reports back via
  // onResult/onError instead of routing through a central App.tsx patch callback.
  const selection = stepDataOf(stepData, 'select-method')
  const confirmPrompt = confirmPromptOf(stepDataOf(stepData, 'confirm')?.prompt)
  const stepMessage = stepDataOf(stepData, 'message')?.message
  const message = stepMessage ? resolveText(stepMessage) : carriedMessage

  const toolCtx: ToolRenderContext | undefined =
    dpop && next?.type === 'tool' && next.toolId
      ? {
          step: next.step,
          toolId: next.toolId,
          toolSessionId: next.toolSessionId ?? activeTool?.toolSessionId,
          proof: { kind: 'dpop', dpop },
          stepData,
          message,
          demo,
          onResult: (response) => applyResponse(response, next.toolId),
          onError: (message) => setError(message),
          onSkip: activeTool ? handleAbandonTool : undefined,
        }
      : undefined

  // The demo column's "why / what / who" for whatever the phone shows right now (StepExplanation):
  // a tool step explains itself (ToolModule.explain), the orchestrator's own screens are explained here.
  const stepExplanation = ((): { idleReason?: string; does: string; actor: string; details?: ReactNode; technical?: string } | undefined => {
    if (toolCtx) {
      const explained = explainToolStep(toolCtx.toolId, toolCtx.step)
      return explained && { ...explained, technical: `Tool ${toolCtx.toolId} · ${toolCtx.step}` }
    }
    const technical = next?.type === 'orchestrator' ? `Orchestrator ${next.context} · ${next.step}` : undefined
    if (!channelSessionId) {
      if (pendingPairingCode) {
        return {
          idleReason: t('Die App wurde über den QR-Code eines Browsers geöffnet.'),
          does: t('Bestätigen eröffnet eine Sitzung beim Orchestrator, um die wartende Anmeldung im Browser freizugeben.'),
          actor: t('Sie. Noch läuft keine Sitzung.'),
        }
      }
      return deviceLink?.linked
        ? {
            idleReason: t('Dieses Gerät ist mit einem Konto verbunden - deshalb bietet die App gleich das Anmelden an.'),
            does: t('Anmelden eröffnet eine Sitzung beim Orchestrator, der das Verfahren dieses Geräts vorschlägt.'),
            actor: t('Sie. Noch läuft keine Sitzung.'),
          }
        : {
            idleReason: t('Dieses Gerät gehört noch zu keinem Konto.'),
            does: t('Anmelden sucht ein bestehendes Konto, Registrieren legt ein neues an. Beides eröffnet eine Sitzung beim Orchestrator.'),
            actor: t('Sie. Noch läuft keine Sitzung.'),
          }
    }
    if (uiComponent === 'select-method') {
      return {
        does: t('Der Orchestrator zeigt die Verfahren, die dieser Schritt zulässt - nur solche, die diese App kann und die noch nicht abgelehnt wurden.'),
        actor: t('Sie wählen. Der Orchestrator wartet.'),
        // Only here does "not offered" explain something: why a method is missing from this choice.
        details: <UnavailableTools channel="APP" availableTools={availableTools} />,
        technical,
      }
    }
    if (uiComponent === 'prompt') {
      return {
        does: t('Eine Ja/Nein-Rückfrage des Orchestrators. Ihr Text kommt vom Backend, damit er sich ohne neue App-Version ändern lässt.'),
        actor: t('Sie antworten. Der Orchestrator wartet.'),
        technical,
      }
    }
    if (uiComponent === 'authentication-completed') {
      return {
        idleReason: t('Die Anmeldung ist abgeschlossen, gerade läuft kein Vorgang.'),
        does: t('Die App ruft Daten mit ihrem AccessToken ab. Das Token ist an den Schlüssel der App gebunden (DPoP) und nützt ohne ihn nichts.'),
        actor: t('Sie. Sicherheitsniveau erhöhen, Verfahren ändern oder Abmelden starten je einen neuen Vorgang.'),
        technical,
      }
    }
    return undefined
  })()

  return (
    <DemoProvider>
      {(setDemoTarget) => (
        <div className="app-frame channel-app">
          <ChannelNav badge={`📱 ${t('App-Kanal')}`} />
          <div className="app-stage">
            <div className="app-stage__phone">
              <PhoneFrame title="Demo">
                {stepExplanation && (
                  <Demo>
                    <StepExplanation
                      journeys={demo?.journeys}
                      {...stepExplanation}
                      journeyTitle={journeyContextKey ? journeyContextLabel(journeyContextKey) : undefined}
                      journeyDiagram={
                        journeyContextKey && (
                          <DiagramHint spec={JOURNEY_DIAGRAMS[journeyContextKey]} current={journeyContextCurrentStep} inline openDown>
                            <span className="diagram-hint-trigger" tabIndex={0} aria-label={t('Ablauf dieses Vorgangs als Diagramm anzeigen')}>
                              ℹ️
                            </span>
                          </DiagramHint>
                        )
                      }
                    />
                  </Demo>
                )}
                {error && (
                  <div className="card error-card">
                    <h2>{t('Fehler')}</h2>
                    <p>{error}</p>
                  </div>
                )}

                {!channelSessionId && (
                  // What the app itself would show in its current state (docs/10-frontend.md): the
                  // way in it offers this device. Every other way in is in the demo column.
                  <div className="card app-home">
                    {pendingPairingCode ? (
                      <>
                        <h2>{t('Web-Login bestätigen')}</h2>
                        <p>{t('Sie haben einen QR-Code gescannt. Bestätigen Sie die Anmeldung im Browser mit dieser App.')}</p>
                        <p className="hint">{t('Pairing-Code: {code}', { code: pendingPairingCode })}</p>
                        {deviceLink?.linked === false ? (
                          // Confirming needs an account on this device - nothing to offer here but the
                          // way back; signing in is the ordinary start screen's job.
                          <>
                            <p>{t('Diese App ist noch mit keinem Konto verbunden. Melden Sie sich zuerst an und scannen Sie den QR-Code danach erneut.')}</p>
                            <div className="form-actions">
                              <button className="secondary" onClick={handleForgetPairingCode}>
                                {t('Abbrechen')}
                              </button>
                            </div>
                          </>
                        ) : (
                          <>
                            <div className="form-actions app-home__actions">
                              <button onClick={() => handleStart('confirmPeerLogin')}>{t('Anmeldung bestätigen')}</button>
                              <button className="secondary" onClick={handleForgetPairingCode}>
                                {t('Abbrechen')}
                              </button>
                            </div>
                            <ButtonDiagrams entries={[{ label: t('Anmeldung bestätigen'), diagram: 'confirmPeerLogin' }]} />
                          </>
                        )}
                      </>
                    ) : deviceLink?.linked ? (
                      <>
                        <h2>{t('Willkommen zurück')}</h2>
                        <p>
                          {deviceLink.personName
                            ? t('Dieses Gerät ist mit dem Konto von {name} verbunden.', { name: deviceLink.personName })
                            : t('Dieses Gerät ist mit Ihrem Konto verbunden.')}
                        </p>
                        {/* The device's own way in first; the two without it (a lookup login, a
                            fresh registration) stay reachable - same wording as on an unlinked device.
                            Each label says what it does: whose account, by which means, or a new one. */}
                        <div className="form-actions app-home__actions">
                          <button onClick={() => handleStart('auto')}>
                            {deviceLink.personName ? t('Als {name} anmelden', { name: deviceLink.personName }) : t('Mit diesem Gerät anmelden')}
                          </button>
                          <button className="secondary" onClick={() => handleStart('login')}>
                            {t('Mit E-Mail-Adresse anmelden')}
                          </button>
                          <button className="secondary" onClick={() => handleStart('register')}>
                            {t('Anderes Konto benutzen')}
                          </button>
                        </div>
                        <ButtonDiagrams
                          entries={[
                            {
                              label: deviceLink.personName ? t('Als {name} anmelden', { name: deviceLink.personName }) : t('Mit diesem Gerät anmelden'),
                              diagram: 'auto',
                            },
                            { label: t('Mit E-Mail-Adresse anmelden'), diagram: 'login' },
                            { label: t('Anderes Konto benutzen'), diagram: 'register' },
                          ]}
                        />
                        {/* Like reinstalling the app: the device key goes, so the orchestrator no
                            longer recognizes this device - the account itself stays untouched. */}
                        {confirmingReset ? (
                          <div className="app-home__reset">
                            <p className="hint">
                              {t('Danach erkennt die App Ihr Konto nicht mehr. Anmelden können Sie sich weiter mit Ihrer E-Mail-Adresse.')}
                            </p>
                            <div className="form-actions app-home__actions">
                              <button className="destructive" onClick={() => { setConfirmingReset(false); handleRecreateKey() }}>
                                {t('Zurücksetzen')}
                              </button>
                              <button className="secondary" onClick={() => setConfirmingReset(false)}>
                                {t('Abbrechen')}
                              </button>
                            </div>
                          </div>
                        ) : (
                          <button className="link-button" onClick={() => setConfirmingReset(true)}>
                            {t('Dieses Gerät zurücksetzen')}
                          </button>
                        )}
                      </>
                    ) : (
                      <>
                        <h2>{t('Willkommen')}</h2>
                        <p>{t('Melden Sie sich mit Ihrem Konto an, oder legen Sie ein neues an.')}</p>
                        <div className="form-actions app-home__actions">
                          <button onClick={() => handleStart('login')}>{t('Mit E-Mail-Adresse anmelden')}</button>
                          <button className="secondary" onClick={() => handleStart('register')}>
                            {t('Neues Konto anlegen')}
                          </button>
                        </div>
                        <ButtonDiagrams
                          entries={[
                            { label: t('Mit E-Mail-Adresse anmelden'), diagram: 'login' },
                            { label: t('Neues Konto anlegen'), diagram: 'register' },
                          ]}
                        />
                      </>
                    )}
                  </div>
                )}

                {uiComponent === 'select-method' && selection && (
                  <SelectMethodView
                    options={selection.options}
                    title={selection.title ? resolveText(selection.title) : t('Verfahren wählen')}
                    description={selection.description ? resolveText(selection.description) : undefined}
                    onSelect={handleSelectMethod}
                  />
                )}

                {toolCtx && renderToolStep(toolCtx)}

                {uiComponent === 'prompt' && confirmPrompt && (
                  <PromptView prompt={confirmPrompt} onAnswer={handleAnswer} />
                )}

                {uiComponent === 'authentication-completed' && dpop && channelSessionId && (
                  <AuthenticationCompletedView
                    dpop={dpop}
                    channelSessionId={channelSessionId}
                    currentAcr={currentAcr}
                    currentAmr={currentAmr}
                    activeMethods={activeMethods}
                    demo={demo}
                    view={accountView}
                    onNavigate={setAccountView}
                    onAddMethod={handleAddMethod}
                    onDeactivateMethod={handleDeactivateMethod}
                    onDeleteAccount={handleDeleteAccount}
                    onStepUp={handleStepUp}
                    onPeerLogin={handlePeerLogin}
                    onLogout={handleLogout}
                    manageError={error || undefined}
                    infoMessage={message}
                  />
                )}

                {inToolMode && ((activeTool && alternativesCount > 0) || canCancel) && (
                  <div className="controls sticky-actions">
                    {/* Ein Tool mit eigenem skipLabel zeichnet den Ausweg selbst, direkt neben
                        seinem Absenden-Button (ToolRenderContext.onSkip) - hier unten waere er vom
                        Formular weg und liesse sich zweimal auf der Seite finden. */}
                    {activeTool && alternativesCount > 0 && !metaFor(activeTool.toolId).skipLabel && (
                      <button className="secondary" onClick={handleBack} title={t('Zurück zur Auswahl. Dieses Verfahren bleibt dort wählbar.')}>
                        {t('Zurück')}
                      </button>
                    )}
                    {/* Nicht an channelState gekoppelt (frühere Fassung prüfte channelState === 'AUTHENTICATED', was auf einem
                        laufenden STEP_UP_IN_PROGRESS-Kanal - z.B. mitten in CONFIRM_PEER_LOGIN - nie zutrifft): canCancel allein
                        ist schon der richtige Signalgeber (ChannelService/JourneyService kennen den echten cancelledTo()-Zielzustand,
                        das Frontend muss ihn nicht selbst erraten). Ohne dieses Abbrechen war "Zurück" bei einem
                        Ein-Kandidaten-Tool wie confirm-qr-login der einzige (aber wirkungslose, da es denselben Schritt nur
                        erneut anbietet) Fluchtweg - "Zur Startseite"/"Abmelden" rendern beide bewusst nicht während inToolMode. */}
                    {/* In einer laufenden Registrierung ist das kein "Abbrechen" im Sinne von
                        "diesen Schritt lassen": Die Journey endet, und was sie bis dahin aufgebaut
                        hat - bis hin zur nachgewiesenen Identität - wird weggeworfen. Also heißt der
                        Knopf, was er tut, und fragt einmal nach. */}
                    {canCancel && !discardsRegistration && (
                      <button className="secondary" onClick={handleCancel} title={t('Bricht diesen Vorgang vollständig ab.')}>
                        {t('Abbrechen')}
                      </button>
                    )}
                    {/* Before anything is proven there is nothing to discard - no question, and on
                        the very first screen (no tool running) it is simply the way back. */}
                    {canCancel && discardsRegistration && !hasProvenFactor && (
                      <button className="secondary" onClick={handleLeaveToStart} title={t('Zurück zur Startseite. Bisher ist nichts gespeichert.')}>
                        {activeTool ? t('Abbrechen') : t('Zurück')}
                      </button>
                    )}
                    {canCancel && discardsRegistration && hasProvenFactor && !confirmingDiscard && (
                      <button className="secondary" onClick={() => setConfirmingDiscard(true)} title={t('Beendet die Registrierung. Alles, was dieser Vorgang bisher aufgebaut hat - auch eine bereits nachgewiesene Identität - wird verworfen.')}>
                        {t('Registrierung verwerfen')}
                      </button>
                    )}
                    {canCancel && discardsRegistration && hasProvenFactor && confirmingDiscard && (
                      <>
                        <span className="hint">{t('Alles aus diesem Vorgang geht verloren, auch die nachgewiesene Identität.')}</span>
                        <button className="secondary" onClick={() => { setConfirmingDiscard(false); handleCancel() }}>
                          {t('Verwerfen')}
                        </button>
                        <button onClick={() => setConfirmingDiscard(false)}>{t('Weitermachen')}</button>
                      </>
                    )}
                  </div>
                )}
              </PhoneFrame>
            </div>

            <DemoArea
              targetRef={setDemoTarget}
              session={
                <SessionSummary
                  signedIn={channelState === 'AUTHENTICATED'}
                  name={demo?.session?.personName}
                  acr={demo?.session?.acr ?? currentAcr}
                  amr={demo?.session?.amr ?? currentAmr}
                />
              }
              intro={
                !channelSessionId && (
                  <div className="card welcome-card">
                    <h2>{t('Dieser Tab ist Ihr Smartphone')}</h2>
                    <p>
                      <Tx
                        text={
                          'Stellen Sie sich vor, Sie öffnen die App Ihrer Versicherung. Der Tab spielt diese App: Beim ersten ' +
                          'Aufruf hat er einen {schluessel} erzeugt, der den Browser nie verlässt, und ' +
                          'signiert damit jede Anfrage (DPoP). Ein abgefangenes Token nützt so auf keinem anderen Gerät.'
                        }
                        schluessel={<strong>{t('DPoP-Schlüssel')}</strong>}
                      />
                    </p>
                    <p>
                      <Tx
                        text={
                          'Beim ersten Mal {registrieren} Sie sich: ausweisen (Freischaltcode aus dem Brief, eID oder Nect), ' +
                          'E-Mail-Adresse bestätigen und ein Anmeldeverfahren einrichten. Danach {anmelden} - auf diesem Gerät ' +
                          'auch automatisch. Echt ist dabei der Orchestrator mit allen Regeln; simuliert sind Handy, SMS und ' +
                          'E-Mail (der Code steht im Formular), Brief, Ausweiskarte, Nect, KOBIL und das Personenverzeichnis.'
                        }
                        registrieren={<strong>{t('registrieren')}</strong>}
                        anmelden={<strong>{t('melden Sie sich an')}</strong>}
                      />
                    </p>
                  </div>
                )
              }
            >
                {/* The ways in are the phone's own buttons (their diagrams are under "Zu diesem
                    Schritt"); only picking up an earlier session is a demo-only way in. */}
                {!channelSessionId && rememberedChannelSessionId && (
                <div className="card">
                <ul className="method-choice-list">
                  {rememberedChannelSessionId && (
                    <li>
                      <button
                        className="method-choice"
                        onClick={() => handleStart('resume')}
                        aria-label={t('Sitzung fortsetzen ({sitzung})', { sitzung: shorten(rememberedChannelSessionId) })}
                      >
                        <span className="method-choice-icon" aria-hidden="true">
                          🔁
                        </span>
                        <span className="method-choice-text">
                          <span className="method-choice-label">{t('Sitzung fortsetzen')}</span>
                          <span className="method-choice-hint">
                            {t('Dort weitermachen, wo Sie aufgehört haben ({sitzung})', { sitzung: shorten(rememberedChannelSessionId) })}
                          </span>
                        </span>
                      </button>
                    </li>
                  )}
                </ul>
              </div>
              )}
              {!channelSessionId && (
                <DeviceIdentityCard jwkThumbprint={jwkThumbprint} onRecreateKey={handleRecreateKey} deviceLink={deviceLink}>
                  {/* Settings of this app for the next journey - and what they leave out. */}
                  <UnavailableTools channel="APP" availableTools={availableTools} />
                  <Disclosure summary={t('Einstellungen für den nächsten Start: Sicherheitsniveau und Verfahren dieser App')}>
                      <p>{t('Wirkt erst auf den nächsten neu gestarteten Vorgang, nicht rückwirkend auf einen laufenden.')}</p>
                      <label className="field-row">
                        {t('Startniveau:')}
                        <select value={requiredAcr} onChange={(e) => setRequiredAcr(e.target.value)}>
                          <option value="">{t('loa1 (Standard)')}</option>
                          <option value="loa2">{t('loa2 (MFA - mehrere Enrollments)')}</option>
                        </select>
                      </label>
                      <ToolAvailabilitySelector availableTools={availableTools} onChange={setAvailableTools} />
                    </Disclosure>
                </DeviceIdentityCard>
              )}
                {channelSessionId && (
                  <JourneyStructureView
                    channelSessionId={channelSessionId}
                    channelState={channelState}
                    journeys={demo?.journeys}
                    next={next}
                    onClear={handleClearChannel}
                    onCancelJourney={canCancel ? handleCancel : undefined}
                  />
                )}
              <DebugSidebar
                channel={{ channelSessionId, channelState, currentAcr, currentAmr, activeMethods, next, stepData, demo, activeTool }}
                log={debugLog}
                open={debugOpen}
                onToggle={() => setDebugOpen((v) => !v)}
              />
            </DemoArea>
          </div>
        </div>
      )}
    </DemoProvider>
  )
}
