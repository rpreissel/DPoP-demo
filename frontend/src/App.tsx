import { useEffect, useRef, useState } from 'react'
import { computeJwkThumbprint, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { ActiveMethodView, ChannelResponse, DemoInfo, Next, StepData } from './types'
import { getUIComponent } from './routing.ts'
import { knownToolIds, renderToolStep } from './tools/registry'
import type { ToolRenderContext } from './tools/types'
import {
  abandonTool,
  activateTool,
  answerPrompt,
  cancelJourney,
  createChannel,
  deactivateMethod,
  describeError,
  getChannel,
  logoutChannel,
  onApiCall,
  raiseRequiredAcr,
  startAccountDeletion,
  startManageMethods,
} from './api.ts'
import { forgetChannelSessionId, loadChannelSessionId, storeChannelSessionId } from './session.ts'
import { shorten } from './format.ts'
import { AuthenticationCompletedView } from './components/AuthenticationCompletedView'
import { DebugSidebar, type DebugEvent } from './components/DebugSidebar'
import { EntryChoiceLinks } from './components/EntryChoiceLinks'
import { SelectMethodView } from './components/SelectMethodView'
import { SessionStatusView } from './components/SessionStatusView'
import { PromptView } from './components/PromptView'
import { ToolAvailabilitySelector } from './components/ToolAvailabilitySelector'
import { AdminToolAvailabilityView } from './components/AdminToolAvailabilityView'
import { WelcomeIntro } from './components/WelcomeIntro'
import { DiagramHint } from './components/DiagramHint'
import { JOURNEY_DIAGRAMS } from './journeyDiagrams'

interface ActiveTool {
  toolSessionId: string
  toolId: string
}

/** Swagger UI isn't proxied by the vite dev server (only /orchestrator is, see vite.config.ts) - in dev it lives on the backend's own port, in a same-origin deployment it's just window.location.origin. */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/** Matches src/main/resources/application.yml - H2 console has no reliable cross-version query-param prefill, so these are shown for manual copy-paste instead. */
const H2_JDBC_URL = 'jdbc:h2:file:./data/dpopdb'
const H2_USER = 'sa'

function App() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [jwkThumbprint, setJwkThumbprint] = useState<string | undefined>()
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
  // SessionStatusView. Unknown after a resume (a prior session's choice isn't remembered), so no
  // hint is offered there rather than guessing.
  const [journeyKind, setJourneyKind] = useState<'auto' | 'register' | 'login' | undefined>()
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
  const [availableTools, setAvailableTools] = useState<string[]>(knownToolIds)
  const [showAdmin, setShowAdmin] = useState(false)
  const [debugOpen, setDebugOpen] = useState(true)
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
    setAlternativesCount(0)
    activatingToolIdRef.current = toolId
    activateTool(dpop, channelSessionId, toolId)
      .then((response) => applyResponse(response, toolId))
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
   */
  useEffect(() => {
    if (!dpop || !channelSessionId) return
    if (next?.type !== 'orchestrator' || next.context !== 'authentication' || next.step !== 'authenticated') return
    if (currentAcr !== undefined) return

    getChannel(dpop, channelSessionId)
      .then((response) => {
        setCurrentAcr(response.channel.currentAcr)
        setCurrentAmr(response.channel.currentAmr)
        setActiveMethods(response.channel.activeMethods)
      })
      .catch((err) => setError(describeError('Sicherheitsdetails laden fehlgeschlagen', err)))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, currentAcr])

  /**
   * Every explicit way a channel comes into existence (docs/04-orchestrierung.md, lookup-based
   * login): "resume" reads a remembered channelSessionId (GET), the rest each mint a brand-new
   * channel with the corresponding `intent` - the backend's AuthIntent name (AuthIntent.fromRequest),
   * "auto" omits it (today's default: DeviceAccountLink found -> LOGIN, else REGISTRATION).
   */
  async function handleStart(mode: 'resume' | 'auto' | 'login' | 'register') {
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
      const intent = mode === 'auto' ? undefined : mode === 'login' ? 'lookup_login' : mode
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
      await logoutChannel(dpop, channelSessionId)
      forgetChannelSessionId()
      setRememberedChannelSessionId(null)
      clearChannelState()
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
      applyResponse(response)
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
      setError(describeError('Account löschen fehlgeschlagen', err))
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

  // Everything a tool's own render() needs (src/tools/registry.ts) - assembled once here from
  // `next`/`activeTool`, each tool module then calls its own api.ts and reports back via
  // onResult/onError instead of routing through a central App.tsx patch callback.
  const toolCtx: ToolRenderContext | undefined =
    dpop && next?.type === 'tool' && next.toolId
      ? {
          step: next.step,
          toolId: next.toolId,
          toolSessionId: next.toolSessionId ?? activeTool?.toolSessionId,
          dpop,
          stepData,
          demo,
          onResult: (response) => applyResponse(response, next.toolId),
          onError: (message) => setError(message),
        }
      : undefined

  return (
    <div className="app-shell">
      <div className="app-main">
        <div className="app">
          <header className="app-header">
            <h1>Identity Journey</h1>
            <p>
              Identifikation, Authentifizierung und Step-up zum Ausprobieren - mehrere Verfahren, deren
              Ablauf das Backend als Journey steuert (DPoP-gesichert).
            </p>
          </header>

          {showAdmin && <AdminToolAvailabilityView />}

          {error && (
            <div className="card error-card">
              <h2>Fehler</h2>
              <p>{error}</p>
            </div>
          )}

          {!showAdmin && (
          <>
          <div className="card">
            <h2>Geräte-Identität</h2>
            <ul className="status-list">
              <li>
                <span className="label">JWK Thumbprint</span>
                <span className="value-with-action">
                  <span className="value" title={jwkThumbprint}>
                    {shorten(jwkThumbprint)}
                  </span>
                  <button className="secondary small" onClick={handleRecreateKey}>
                    Neu erzeugen
                  </button>
                </span>
              </li>
            </ul>
          </div>

          {channelSessionId ? (
            <>
              <SessionStatusView
                channelSessionId={channelSessionId}
                state={channelState}
                next={next}
                journeyKind={journeyKind}
                onClear={handleClearChannel}
              />
              {!inToolMode && <EntryChoiceLinks channelState={channelState} onChooseIntent={handleStart} />}
              <div className="controls sticky-actions">
                {inToolMode && activeTool && alternativesCount > 0 && (
                  <button className="secondary" onClick={handleAbandonTool} title="Bricht nur diesen einen Schritt ab, der Vorgang selbst läuft weiter (z. B. mit einer anderen Methode).">
                    Anderes Verfahren
                  </button>
                )}
                {canCancel && (
                  <button className="secondary" onClick={handleCancel} title="Startet diesen Vorgang von vorne, mit demselben Ziel (z. B. erneut identifizieren).">
                    Vorgang neu starten
                  </button>
                )}
                {channelState !== 'AUTHENTICATED' && (
                  <button className="secondary" onClick={handleClearChannel} title="Verlässt den Vorgang ganz und geht zurück zur Startauswahl.">
                    Zur Startseite
                  </button>
                )}
                {!inToolMode && channelState === 'AUTHENTICATED' && (
                  <button className="secondary" onClick={handleLogout}>
                    Logout
                  </button>
                )}
              </div>
            </>
          ) : (
            <>
              <WelcomeIntro />
              <div className="card">
                <h2>Wie möchten Sie starten?</h2>
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
                    <DiagramHint spec={JOURNEY_DIAGRAMS.auto}>
                      <button className="method-choice" onClick={() => handleStart('auto')} aria-label="Verbinden (automatisch)">
                        <span className="method-choice-icon" aria-hidden="true">
                          🚀
                        </span>
                        <span className="method-choice-text">
                          <span className="method-choice-label">Verbinden (automatisch)</span>
                          <span className="method-choice-hint">
                            Empfohlen: Kennt dieses Gerät schon einen Account, meldet es sich direkt an - sonst startet eine
                            Registrierung.
                          </span>
                        </span>
                      </button>
                    </DiagramHint>
                  </li>
                  <li>
                    <DiagramHint spec={JOURNEY_DIAGRAMS.register}>
                      <button className="method-choice" onClick={() => handleStart('register')} aria-label="Neuen Account registrieren">
                        <span className="method-choice-icon" aria-hidden="true">
                          ✨
                        </span>
                        <span className="method-choice-text">
                          <span className="method-choice-label">Neuen Account registrieren</span>
                          <span className="method-choice-hint">Immer einen frischen Demo-Account anlegen, auch auf diesem Gerät.</span>
                        </span>
                      </button>
                    </DiagramHint>
                  </li>
                  <li>
                    <DiagramHint spec={JOURNEY_DIAGRAMS.login}>
                      <button className="method-choice" onClick={() => handleStart('login')} aria-label="Login ohne DPoP">
                        <span className="method-choice-icon" aria-hidden="true">
                          🌐
                        </span>
                        <span className="method-choice-text">
                          <span className="method-choice-label">Login ohne DPoP</span>
                          <span className="method-choice-hint">Auf einem neuen/anderen Gerät anmelden, per E-Mail statt Geräte-Schlüssel.</span>
                        </span>
                      </button>
                    </DiagramHint>
                  </li>
                </ul>

                <details className="advanced-options">
                  <summary>Erweiterte Optionen</summary>
                  <label className="field-row">
                    Startniveau:
                    <select value={requiredAcr} onChange={(e) => setRequiredAcr(e.target.value)}>
                      <option value="">loa1 (Standard)</option>
                      <option value="loa2">loa2 (MFA - mehrere Enrollments)</option>
                    </select>
                  </label>
                  <ToolAvailabilitySelector availableTools={availableTools} onChange={setAvailableTools} />
                </details>
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
              manageError={error || undefined}
              infoMessage={typeof stepData?.message === 'string' ? stepData.message : undefined}
            />
          )}
          </>
          )}

          <footer className="dev-tools">
            <button
              className="admin-toggle"
              onClick={() => setShowAdmin((v) => !v)}
              title={showAdmin ? 'Zurück zur App' : 'Tools serverseitig sperren/freigeben, um Ausfälle zu simulieren'}
            >
              {showAdmin ? '← Zurück zur App' : 'Admin'}
            </button>
            <a
              className="admin-toggle"
              href={`${BACKEND_ORIGIN}/swagger-ui/index.html`}
              target="_blank"
              rel="noreferrer"
              title="Backend-API-Dokumentation (Swagger/OpenAPI UI)"
            >
              API-Doku
            </a>
            <a
              className="admin-toggle"
              href={`${BACKEND_ORIGIN}/h2-console`}
              target="_blank"
              rel="noreferrer"
              title={`H2-Datenbankkonsole - Login dort eintragen:\nJDBC URL: ${H2_JDBC_URL}\nUser: ${H2_USER}\nPassword: (leer)`}
            >
              H2-Konsole ({H2_JDBC_URL}, User {H2_USER}, kein Passwort)
            </a>
          </footer>
        </div>
      </div>

      <DebugSidebar
        channel={{ channelSessionId, channelState, currentAcr, currentAmr, activeMethods, next, stepData, demo, activeTool }}
        log={debugLog}
        open={debugOpen}
        onToggle={() => setDebugOpen((v) => !v)}
      />
    </div>
  )
}

export default App
