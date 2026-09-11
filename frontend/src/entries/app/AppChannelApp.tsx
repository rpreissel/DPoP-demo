import { useCallback, useEffect, useRef, useState } from 'react'
import { computeJwkThumbprint, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from '../../dpop.ts'
import '../../App.css'
import type { ActiveMethodView, ChannelResponse, DemoInfo, DeviceLinkResponse, Next, StepData } from '../../types'
import { getUIComponent } from '../../routing.ts'
import { knownToolIds, renderToolStep } from '../../tools/registry'
import type { ToolRenderContext } from '../../tools/types'
import {
  abandonTool,
  activateTool,
  answerPrompt,
  cancelJourney,
  createChannel,
  deactivateMethod,
  describeError,
  getAccountJourneyLog,
  getChannel,
  getDeviceLink,
  getJourneyLog,
  startLogout,
  onApiCall,
  raiseRequiredAcr,
  startAccountDeletion,
  startManageMethods,
  startPeerLogin,
} from '../../api.ts'
import {
  forgetChannelSessionId,
  loadAvailableTools,
  loadChannelSessionId,
  storeAvailableTools,
  storeChannelSessionId,
  storePendingPairingCode,
} from '../../session.ts'
import { shorten } from '../../format.ts'
import { AppChannelFrame } from '../../components/AppChannelFrame'
import { AuthenticationCompletedView } from '../../components/AuthenticationCompletedView'
import { DebugSidebar, type DebugEvent } from '../../components/DebugSidebar'
import { EntryChoiceLinks } from '../../components/EntryChoiceLinks'
import { SelectMethodView } from '../../components/SelectMethodView'
import { JourneyStructureView } from '../../components/JourneyStructureView'
import { JourneyLogView } from '../../components/JourneyLogView'
import { PromptView } from '../../components/PromptView'
import { ToolAvailabilitySelector } from '../../components/ToolAvailabilitySelector'
import { AdminToolAvailabilityView } from '../../components/AdminToolAvailabilityView'
import { UnavailableTools } from '../../components/UnavailableTools'
import { DeviceIdentityCard } from '../../components/DeviceIdentityCard'
import { DiagramHint } from '../../components/DiagramHint'
import { CURRENT_STEP_BY_STATE_TYPE, currentJourneyDiagramKey, journeyContextLabel, JOURNEY_DIAGRAMS } from '../../journeyDiagrams'

interface ActiveTool {
  toolSessionId: string
  toolId: string
}

type SubTab = 'demo' | 'journeylog' | 'settings'
const SUB_TABS: SubTab[] = ['demo', 'journeylog', 'settings']

/** The sub-tab lives in the URL hash ("journeylog"/"settings", bare/empty meaning "demo") so a reload or a shared link keeps/opens the same place. */
function subFromHash(): SubTab {
  const raw = window.location.hash.slice(1)
  return (SUB_TABS as string[]).includes(raw) ? (raw as SubTab) : 'demo'
}

/** Swagger UI isn't proxied by the vite dev server (only /orchestrator is, see vite.config.ts) - in dev it lives on the backend's own port, in a same-origin deployment it's just window.location.origin. */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/** Matches src/main/resources/application.yml - H2 console has no reliable cross-version query-param prefill, so these are shown for manual copy-paste instead. */
const H2_JDBC_URL = 'jdbc:h2:file:./data/dpopdb'
const H2_USER = 'sa'

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
  const [demo, setDemo] = useState<DemoInfo | undefined>()
  const [activeTool, setActiveTool] = useState<ActiveTool | null>(null)
  // Which entry choice started the current channel - drives the journey-shape hover hint in
  // JourneyStructureView. Unknown after a resume (a prior session's choice isn't remembered), so no
  // hint is offered there rather than guessing.
  const [journeyKind, setJourneyKind] = useState<'auto' | 'register' | 'login' | 'confirmPeerLogin' | undefined>()
  // Set from the WEB channel's demo link (?pairingCode=..., docs/07-betrieb.md #5) -
  // the Keycloak-side QR page's demo link points straight at this app's own /app/ entry, so opening
  // it lands here directly instead of a fictitious native deep-link scheme. Only captured/surfaced
  // for now (shown as a banner near the entry choice) besides driving the auto-start effect below;
  // actually submitting it as confirm-qr-login's own `pairingCode` field is bmh.4/bmh.6.
  const [pendingPairingCode, setPendingPairingCode] = useState<string | undefined>()
  // How many OTHER candidates existed when the current activeTool was reached - "Anderes
  // Verfahren" only makes sense to offer when this is > 0, otherwise abandoning would just
  // re-offer the very same tool (a mandatory single-candidate step is its own only fallback).
  // Set at the two places a tool actually becomes active: handleSelectMethod (the user just saw
  // the full candidate list) and the auto-activate effect (0 for a direct single-candidate skip,
  // since the backend only ever collapses straight to a tool when nothing else was on offer).
  const [alternativesCount, setAlternativesCount] = useState(0)
  const [error, setError] = useState('')
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
  const [sub, setSubState] = useState<SubTab>(() => subFromHash())
  const appJourneyLogFetcher = useCallback(
    () => (channelSessionId ? getAccountJourneyLog(dpop!, channelSessionId) : getJourneyLog(dpop!)),
    [channelSessionId, dpop],
  )
  const [debugOpen, setDebugOpen] = useState(false)
  const [debugLog, setDebugLog] = useState<DebugEvent[]>([])
  const debugIdRef = useRef(0)
  // Drives the "Sitzung fortsetzen" button's visibility on the "no channel" screen - kept in
  // sync explicitly (not derived from channelSessionId) since it must survive Clear/Logout
  // clearing the in-memory state while still reflecting localStorage accurately afterwards.
  const [rememberedChannelSessionId, setRememberedChannelSessionId] = useState(() => loadChannelSessionId())

  /** Keeps the URL hash in sync so a reload, a shared link, or the browser's own back/forward button all land on the right place. */
  function setActiveTab(newSub: SubTab) {
    setSubState(newSub)
    const hash = newSub === 'demo' ? '' : newSub
    if (window.location.hash.slice(1) !== hash) window.location.hash = hash
  }

  useEffect(() => {
    const onHashChange = () => setSubState(subFromHash())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

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
    setCurrentAcr(undefined)
    setCurrentAmr(undefined)
    setActiveMethods(undefined)
    setNext(undefined)
    setStepData(undefined)
    setDemo(undefined)
    setActiveTool(null)
    setAlternativesCount(0)
    setJourneyKind(undefined)
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
    setChannelSessionId(response.channel.channelSessionId)
    storeChannelSessionId(response.channel.channelSessionId)
    setRememberedChannelSessionId(response.channel.channelSessionId)
    setChannelState(response.channel.state)
    setCurrentAcr(response.channel.currentAcr)
    setCurrentAmr(response.channel.currentAmr)
    setActiveMethods(response.channel.activeMethods)
    setNext(response.next)
    setStepData(response.stepData)
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
    init().catch((err) => setError(describeError('Init error', err)))
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
        if (active) setDeviceLink(link)
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
    if (!intentParam && !pairingCode) return
    urlEntryHandledRef.current = true

    if (pairingCode) {
      setPendingPairingCode(pairingCode)
      storePendingPairingCode(pairingCode)
      logEvent('QR-Pairing-Code aus Link übernommen', { response: { pairingCode } })
    }

    params.delete('intent')
    params.delete('pairingCode')
    const query = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (query ? `?${query}` : '') + window.location.hash)

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
    setJourneyKind('confirmPeerLogin')
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
          .catch((err) => setError(describeError('Web-Login-Bestätigung fehlgeschlagen', err)))
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
      // Resuming (e.g. after a reload) - whether alternatives existed originally is lost, so
      // conservatively assume none rather than offer a switch that might be a no-op.
      setAlternativesCount(0)
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: next.toolId })
      return
    }

    const toolId = next.toolId
    // A single candidate offered directly (no selection screen, e.g. FastAccessState.PreferredAuth
    // suggesting the linked device) is NOT the same as "no alternative exists" - JourneyService
    // only skips the selection screen because there is exactly one OFFERED candidate right now;
    // abandoning is still a normal, backend-handled fallback for every such state (every intent's
    // JourneyEvent.Abandoned handler resolves cleanly, worst case Decision.Cancel - never an
    // error). So "Anderes Verfahren" stays offered here, same as after an explicit selection.
    setAlternativesCount(1)
    activatingToolIdRef.current = toolId
    const pendingMessage = stepData?.message
    activateTool(dpop, channelSessionId, toolId)
      .then((response) => {
        // Preserve the journey-level context message (e.g. "E-Mail-Bestätigung ausstehend")
        // across auto-activation: the tool endpoint's own response typically has no message,
        // so the one from the offering state would otherwise be lost.
        if (typeof pendingMessage === 'string' && !response.stepData?.message) {
          response = { ...response, stepData: { ...response.stepData, message: pendingMessage } }
        }
        applyResponse(response, toolId)
      })
      .catch((err) => setError(describeError('Tool activation failed', err)))
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
      .catch((err) => setError(describeError('Sicherheitsdetails laden fehlgeschlagen', err)))
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
      setJourneyKind(mode)
      const response = await createChannel(dpop, requiredAcr || undefined, intent, availableTools)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Start fehlgeschlagen', err))
    }
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
      setError(describeError('Key-Neuerzeugung fehlgeschlagen', err))
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
      setError(describeError('Logout failed', err))
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
      setError(describeError('Step-up fehlgeschlagen', err))
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
        forgetChannelSessionId()
        setRememberedChannelSessionId(null)
        applyResponse(response)
      } else {
        applyResponse(response)
      }
    } catch (err) {
      setError(describeError('Antwort fehlgeschlagen', err))
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
      setError(describeError('Hinzufügen fehlgeschlagen', err))
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
      setError(describeError('Web-Login-Bestätigung fehlgeschlagen', err))
    }
  }

  async function handleDeactivateMethod(methodInstanceId: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await deactivateMethod(dpop, channelSessionId, methodInstanceId)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Deaktivieren fehlgeschlagen', err))
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
      setError(describeError('Konto löschen fehlgeschlagen', err))
    }
  }

  /**
   * Declines the running tool - the chain's own action, distinct from Abbrechen. On a FAST
   * fallback state this moves along the chain (other auth methods, then identification); on a
   * mandatory one it just brings the full choice back. Abbrechen, by contrast, ends the whole
   * journey and therefore restarts the SAME intent - which on a fallback state means landing right
   * back where you were.
   */
  async function handleAbandonTool() {
    if (!dpop || !activeTool) return
    try {
      setError('')
      const response = await abandonTool(dpop, activeTool.toolSessionId, activeTool.toolId)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Wechsel fehlgeschlagen', err))
    }
  }

  async function handleCancel() {
    if (!dpop || !channelSessionId) return
    try {
      const response = await cancelJourney(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Cancel failed', err))
    }
  }

  async function handleSelectMethod(toolId: string) {
    if (!dpop || !channelSessionId) return
    try {
      // The options just shown minus the one being picked = how many real alternatives remain.
      setAlternativesCount(Math.max(0, (stepData?.options?.length ?? 1) - 1))
      const response = await activateTool(dpop, channelSessionId, toolId)
      applyResponse(response, toolId)
    } catch (err) {
      setError(describeError('Tool activation failed', err))
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
  const journeyContextKey = currentJourneyDiagramKey(demo?.journeys, journeyKind)
  // Same box the innermost journey's own hint highlights in JourneyStructureView (JourneyDebugStep.stateType) - the context banner's diagram popover marks it too, not just the shape.
  const journeyContextCurrentStep = journeyContextKey ? CURRENT_STEP_BY_STATE_TYPE[journeyContextKey]?.[demo?.journeys?.at(-1)?.stateType ?? ''] : undefined

  // Everything a tool's own render() needs (src/tools/registry.ts) - assembled once here from
  // `next`/`activeTool`, each tool module then calls its own api.ts and reports back via
  // onResult/onError instead of routing through a central App.tsx patch callback.
  const toolCtx: ToolRenderContext | undefined =
    dpop && next?.type === 'tool' && next.toolId
      ? {
          step: next.step,
          toolId: next.toolId,
          toolSessionId: next.toolSessionId ?? activeTool?.toolSessionId,
          proof: { kind: 'dpop', dpop },
          stepData,
          demo,
          onResult: (response) => applyResponse(response, next.toolId),
          onError: (message) => setError(message),
        }
      : undefined

  return (
    <div className="app-shell">
      <div className="app-main">
        <AppChannelFrame sub={sub} onSelectTab={(s) => setActiveTab(s as SubTab)} onBack={() => { window.location.href = '/' }}>
          {sub === 'journeylog' && (
            <JourneyLogView fetchLog={dpop ? appJourneyLogFetcher : null} />
          )}

          {sub === 'settings' && (
            <>
              <AdminToolAvailabilityView />
              <div className="card">
                <h2>Demo-Konfiguration (App-Kanal)</h2>
                <p>Wirkt erst auf den nächsten im Demo-Reiter neu gestarteten Vorgang, nicht rückwirkend auf einen laufenden.</p>
                <label className="field-row">
                  Startniveau:
                  <select value={requiredAcr} onChange={(e) => setRequiredAcr(e.target.value)}>
                    <option value="">loa1 (Standard)</option>
                    <option value="loa2">loa2 (MFA - mehrere Enrollments)</option>
                  </select>
                </label>
                <ToolAvailabilitySelector availableTools={availableTools} onChange={setAvailableTools} />
              </div>
              <div className="card">
                <h2>Entwickler-Werkzeuge</h2>
                <ul className="status-list">
                  <li>
                    <span className="label">API-Doku</span>
                    <a className="value" href={`${BACKEND_ORIGIN}/swagger-ui/index.html`} target="_blank" rel="noreferrer">
                      Swagger/OpenAPI UI
                    </a>
                  </li>
                  <li>
                    <span className="label">H2-Konsole</span>
                    <span className="value" title={`JDBC URL: ${H2_JDBC_URL}\nUser: ${H2_USER}\nPassword: (leer)`}>
                      <a href={`${BACKEND_ORIGIN}/h2-console`} target="_blank" rel="noreferrer">
                        öffnen
                      </a>{' '}
                      ({H2_JDBC_URL}, User {H2_USER}, kein Passwort)
                    </span>
                  </li>
                </ul>
              </div>
            </>
          )}

          {error && sub === 'demo' && (
            <div className="card error-card">
              <h2>Fehler</h2>
              <p>{error}</p>
            </div>
          )}

          {sub === 'demo' && (
          <>
          <UnavailableTools availableTools={availableTools} />

          {channelSessionId ? (
            <>
              {(journeyContextKey || channelState !== 'AUTHENTICATED') && (
                <div className="journey-context">
                  <span>
                    {journeyContextKey && (
                      <>
                        Aktueller Vorgang: <strong>{journeyContextLabel(journeyContextKey)}</strong>
                        <DiagramHint spec={JOURNEY_DIAGRAMS[journeyContextKey]} current={journeyContextCurrentStep} inline>
                          <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf dieses Vorgangs als Diagramm anzeigen">
                            ℹ️
                          </span>
                        </DiagramHint>
                      </>
                    )}
                  </span>
                  {channelState !== 'AUTHENTICATED' && (
                    <button className="secondary small" onClick={handleClearChannel} title="Verlässt den Vorgang ganz und geht zurück zur Startauswahl.">
                      Zur Startseite
                    </button>
                  )}
                </div>
              )}
              {!inToolMode && <EntryChoiceLinks channelState={channelState} onChooseIntent={handleStart} />}

              {channelState === 'LOGGED_OUT' && (
                <div className="card">
                  <h2>Abgemeldet</h2>
                  <p>Sie wurden erfolgreich abgemeldet. Ihre Sitzung wurde beendet.</p>
                  <div className="form-actions" style={{ marginTop: '1rem' }}>
                    <button onClick={handleClearChannel}>Zur Startseite</button>
                  </div>
                </div>
              )}
              <div className="controls sticky-actions">
                {inToolMode && activeTool && alternativesCount > 0 && (
                  <button className="secondary" onClick={handleAbandonTool} title="Bricht nur diesen einen Schritt ab, der Vorgang selbst läuft weiter (z. B. mit einer anderen Methode).">
                    Anderes Verfahren
                  </button>
                )}
                {!inToolMode && channelState === 'AUTHENTICATED' && uiComponent !== 'prompt' && (
                  <button className="secondary" onClick={handleLogout} title="Beendet den Channel serverseitig - eine neue Sitzung braucht danach einen frischen Login.">
                    Abmelden
                  </button>
                )}
              </div>
            </>
          ) : (
            <>
              <DeviceIdentityCard jwkThumbprint={jwkThumbprint} onRecreateKey={handleRecreateKey} deviceLink={deviceLink} />
              <div className="card">
                <h2>Wie möchten Sie starten?</h2>
                {pendingPairingCode && (
                  <p className="hint">
                    QR-Code erkannt (Pairing-Code {pendingPairingCode}) - wählen Sie „Web-Login per QR bestätigen".
                  </p>
                )}
                <ul className="method-choice-list">
                  {rememberedChannelSessionId && (
                    <li>
                      <button
                        className="method-choice"
                        onClick={() => handleStart('resume')}
                        aria-label={`Sitzung fortsetzen (${shorten(rememberedChannelSessionId)})`}
                      >
                        <span className="method-choice-icon" aria-hidden="true">
                          🔁
                        </span>
                        <span className="method-choice-text">
                          <span className="method-choice-label">Sitzung fortsetzen</span>
                          <span className="method-choice-hint">
                            Dort weitermachen, wo Sie aufgehört haben ({shorten(rememberedChannelSessionId)})
                          </span>
                        </span>
                      </button>
                    </li>
                  )}
                  <li>
                    <button className="method-choice" onClick={() => handleStart('auto')} aria-label="Automatisch anmelden">
                      <span className="method-choice-icon" aria-hidden="true">
                        🚀
                      </span>
                      <span className="method-choice-text">
                        <span className="method-choice-label">
                          Automatisch anmelden
                          <DiagramHint spec={JOURNEY_DIAGRAMS.auto} inline>
                            <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Automatisch anmelden als Diagramm anzeigen">
                              ℹ️
                            </span>
                          </DiagramHint>
                        </span>
                        <span className="method-choice-hint">
                          Empfohlen: Kennt dieses Gerät schon ein Konto, meldet es sich direkt an - sonst startet eine
                          Registrierung.
                        </span>
                      </span>
                    </button>
                  </li>
                  <li>
                    <button className="method-choice" onClick={() => handleStart('register')} aria-label="Neues Konto registrieren">
                      <span className="method-choice-icon" aria-hidden="true">
                        ✨
                      </span>
                      <span className="method-choice-text">
                        <span className="method-choice-label">
                          Neues Konto registrieren
                          <DiagramHint spec={JOURNEY_DIAGRAMS.register} inline>
                            <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Neues Konto registrieren als Diagramm anzeigen">
                              ℹ️
                            </span>
                          </DiagramHint>
                        </span>
                        <span className="method-choice-hint">
                          Durchläuft immer die Registrierung, auch wenn dieses Gerät schon bekannt ist - bei
                          derselben Test-Identität landen Sie wieder auf dem bestehenden Konto.
                        </span>
                      </span>
                    </button>
                  </li>
                  <li>
                    <button className="method-choice" onClick={() => handleStart('login')} aria-label="Neu anmelden">
                      <span className="method-choice-icon" aria-hidden="true">
                        🌐
                      </span>
                      <span className="method-choice-text">
                        <span className="method-choice-label">
                          Neu anmelden
                          <DiagramHint spec={JOURNEY_DIAGRAMS.login} inline>
                            <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Neu anmelden als Diagramm anzeigen">
                              ℹ️
                            </span>
                          </DiagramHint>
                        </span>
                        <span className="method-choice-hint">Ohne dieses Gerät wiederzuerkennen anmelden, per E-Mail und Passwort oder Code.</span>
                      </span>
                    </button>
                  </li>
                  <li>
                    <button
                      className="method-choice"
                      onClick={() => handleStart('confirmPeerLogin')}
                      aria-label="Web-Login per QR bestätigen"
                    >
                      <span className="method-choice-icon" aria-hidden="true">
                        📷
                      </span>
                      <span className="method-choice-text">
                        <span className="method-choice-label">
                          Web-Login per QR bestätigen
                          <DiagramHint spec={JOURNEY_DIAGRAMS.confirmPeerLogin} inline>
                            <span className="diagram-hint-trigger" tabIndex={0} aria-label="Ablauf von Web-Login per QR bestätigen als Diagramm anzeigen">
                              ℹ️
                            </span>
                          </DiagramHint>
                        </span>
                        <span className="method-choice-hint">
                          Ein Browser wartet auf eine Bestätigung von diesem Gerät (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN).
                          Setzt ein hier schon bekanntes Konto voraus.
                        </span>
                      </span>
                    </button>
                  </li>
                </ul>
              </div>
            </>
          )}

          {uiComponent === 'select-method' && stepData?.options && (
            <SelectMethodView
              options={stepData.options}
              title={stepData.title ?? 'Verfahren wählen'}
              description={stepData.description}
              onSelect={handleSelectMethod}
            />
          )}

          {toolCtx && renderToolStep(toolCtx)}

          {uiComponent === 'prompt' && stepData?.prompt && (
            <PromptView prompt={stepData.prompt} onAnswer={handleAnswer} />
          )}

          {uiComponent === 'authentication-completed' && dpop && channelSessionId && (
            <AuthenticationCompletedView
              dpop={dpop}
              channelSessionId={channelSessionId}
              currentAcr={currentAcr}
              currentAmr={currentAmr}
              activeMethods={activeMethods}
              demo={demo}
              onAddMethod={handleAddMethod}
              onDeactivateMethod={handleDeactivateMethod}
              onDeleteAccount={handleDeleteAccount}
              onStepUp={handleStepUp}
              onPeerLogin={handlePeerLogin}
              manageError={error || undefined}
              infoMessage={typeof stepData?.message === 'string' ? stepData.message : undefined}
            />
          )}

          {channelSessionId && (
            <JourneyStructureView
              channelSessionId={channelSessionId}
              channelState={channelState}
              journeys={demo?.journeys}
              next={next}
              journeyKind={journeyKind}
              onClear={handleClearChannel}
              onCancelJourney={canCancel ? handleCancel : undefined}
            />
          )}
          </>
          )}
        </AppChannelFrame>
      </div>

      {sub === 'demo' && (
        <DebugSidebar
          channel={{ channelSessionId, channelState, currentAcr, currentAmr, activeMethods, next, stepData, demo, activeTool }}
          log={debugLog}
          open={debugOpen}
          onToggle={() => setDebugOpen((v) => !v)}
        />
      )}
    </div>
  )
}
