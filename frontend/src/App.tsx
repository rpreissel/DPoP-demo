import React, { useEffect, useRef, useState } from 'react'
import { computeJwkThumbprint, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { ChannelResponse, DemoInfo, Next, StepData } from './types'
import { getUIComponent } from './routing.ts'
import {
  activateTool,
  ApiError,
  cancelProcess,
  createChannel,
  deactivateMethod,
  getChannel,
  logoutChannel,
  onApiCall,
  patchTool,
  raiseRequiredAcr,
  startManageMethods,
} from './api.ts'
import { forgetChannelSessionId, loadChannelSessionId, storeChannelSessionId } from './session.ts'
import { shorten } from './format.ts'
import { AuthenticationCompletedView } from './components/AuthenticationCompletedView'
import { DebugSidebar, type DebugEvent } from './components/DebugSidebar'
import { EmailCodeInputForm } from './components/EmailCodeInputForm'
import { EmailCodeLookupForm } from './components/EmailCodeLookupForm'
import { EmailEnrollForm } from './components/EmailEnrollForm'
import { EmailLookupForm } from './components/EmailLookupForm'
import { EmailPasswordLookupForm } from './components/EmailPasswordLookupForm'
import { EntryChoiceLinks } from './components/EntryChoiceLinks'
import { IdentFscForm } from './components/IdentFscForm'
import { PasswordEnrollForm } from './components/PasswordEnrollForm'
import { PasswordLoginForm } from './components/PasswordLoginForm'
import { SelectMethodView } from './components/SelectMethodView'
import { SessionStatusView } from './components/SessionStatusView'
import { SmsEnrollForm } from './components/SmsEnrollForm'
import { TanInputForm } from './components/TanInputForm'
import { DeviceEnrollForm } from './components/DeviceEnrollForm'
import { DeviceAuthForm } from './components/DeviceAuthForm'

interface ActiveTool {
  toolSessionId: string
  toolId: string
}

/**
 * Renders any thrown error into the UI's error card. ApiErrors carry the server's own message
 * (docs/07-betrieb.md #1); GONE (session/process expired or exhausted) additionally gets a
 * concrete next step, since "Process for this tool session is gone" alone isn't actionable.
 */
function describeError(prefix: string, err: unknown): string {
  if (err instanceof ApiError) {
    const hint = err.status === 410 ? ' Bitte "Kanal leeren" klicken, um neu zu starten.' : ''
    return `${prefix}: ${err.message}${hint}`
  }
  return `${prefix}: ${err instanceof Error ? err.message : String(err)}`
}

function App() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [jwkThumbprint, setJwkThumbprint] = useState<string | undefined>()
  const [channelSessionId, setChannelSessionId] = useState<string | undefined>()
  const [channelState, setChannelState] = useState<string | undefined>()
  const [currentAcr, setCurrentAcr] = useState<string | undefined>()
  const [currentAmr, setCurrentAmr] = useState<string[] | undefined>()
  const [activeMethods, setActiveMethods] = useState<string[] | undefined>()
  const [next, setNext] = useState<Next | undefined>()
  const [stepData, setStepData] = useState<StepData | undefined>()
  const [demo, setDemo] = useState<DemoInfo | undefined>()
  const [activeTool, setActiveTool] = useState<ActiveTool | null>(null)
  const [error, setError] = useState('')
  // Only takes effect on the next channel-creating action (Verbinden/Login ohne DPoP/Registrieren
  // below) - needed to reach enroll-password at all: it requires a confirmed email first, but a
  // single loa1 method already satisfies the default floor and ends registration before password
  // could ever be offered - only requesting loa2 up front keeps the flow going long enough to
  // chain sms/email -> password.
  const [requiredAcr, setRequiredAcr] = useState('')
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
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: next.toolId })
      return
    }

    const toolId = next.toolId
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
    if (next?.type !== 'flow' || next.context !== 'authentication' || next.step !== 'authenticated') return
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
   * channel with the corresponding `intent` - "auto" omits it (today's default: DeviceAccountLink
   * found -> LOGIN, else REGISTRATION).
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
      const intent = mode === 'auto' ? undefined : mode
      const response = await createChannel(dpop, requiredAcr || undefined, intent)
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

  async function handleDeactivateMethod(method: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await deactivateMethod(dpop, channelSessionId, method)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Deaktivieren fehlgeschlagen', err))
    }
  }

  async function handleCancel() {
    if (!dpop || !channelSessionId) return
    try {
      const response = await cancelProcess(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError('Cancel failed', err))
    }
  }

  async function handleSelectMethod(toolId: string) {
    if (!dpop || !channelSessionId) return
    try {
      const response = await activateTool(dpop, channelSessionId, toolId)
      applyResponse(response, toolId)
    } catch (err) {
      setError(describeError('Tool activation failed', err))
    }
  }

  async function handlePatch(body: Record<string, unknown>) {
    if (!dpop || !channelSessionId || !activeTool) return
    try {
      const response = await patchTool(dpop, activeTool.toolSessionId, activeTool.toolId, body)
      applyResponse(response, activeTool.toolId)
    } catch (err) {
      setError(describeError('Request failed', err))
    }
  }

  const uiComponent = getUIComponent(next)
  // Nothing to cancel before a process even started, or once it's already finished.
  const canCancel = !!next && !(next.type === 'flow' && next.context === 'authentication' && next.step === 'authenticated')
  // Once a tool is actively awaiting input - or the user is choosing WHICH tool, i.e.
  // select-method - every other action (bail into a different flow, logout, ...) just competes
  // for attention with the one that matters: Abbrechen.
  const inToolMode = !!activeTool || uiComponent === 'select-method'

  return (
    <div className="app-shell">
      <div className="app-main">
        <div className="app">
          <header className="app-header">
            <h1>DPoP Demo</h1>
            <p>React {React.version} + TypeScript + Spring Boot Modulith</p>
          </header>

          {error && (
            <div className="card error-card">
              <h2>Fehler</h2>
              <p>{error}</p>
            </div>
          )}

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
              <SessionStatusView channelSessionId={channelSessionId} state={channelState} next={next} onClear={handleClearChannel} />
              {inToolMode ? (
                canCancel && (
                  <div className="controls">
                    <button className="secondary" onClick={handleCancel}>
                      Abbrechen
                    </button>
                  </div>
                )
              ) : (
                <>
                  <EntryChoiceLinks channelState={channelState} onChooseIntent={handleStart} />
                  <div className="controls">
                    {canCancel && (
                      <button className="secondary" onClick={handleCancel}>
                        Abbrechen
                      </button>
                    )}
                    {channelState === 'AUTHENTICATED' && (
                      <button className="secondary" onClick={handleLogout}>
                        Logout
                      </button>
                    )}
                  </div>
                </>
              )}
            </>
          ) : (
            <div className="card">
              <h2>Kein Kanal aktiv</h2>
              <p>Es passiert nichts automatisch - wählen Sie, wie der Kanal starten soll.</p>
              <label className="field-row">
                Startniveau:
                <select value={requiredAcr} onChange={(e) => setRequiredAcr(e.target.value)}>
                  <option value="">loa1 (Standard)</option>
                  <option value="loa2">loa2 (MFA - mehrere Enrollments)</option>
                </select>
              </label>
              <div className="form-actions stacked">
                {rememberedChannelSessionId && (
                  <button onClick={() => handleStart('resume')}>Sitzung fortsetzen ({shorten(rememberedChannelSessionId)})</button>
                )}
                <button onClick={() => handleStart('auto')}>Verbinden (automatisch)</button>
                <button className="secondary" onClick={() => handleStart('login')}>
                  Login ohne DPoP
                </button>
                <button className="secondary" onClick={() => handleStart('register')}>
                  Neuen Account registrieren
                </button>
              </div>
            </div>
          )}

          {uiComponent === 'select-method' && stepData?.options && (
            <SelectMethodView options={stepData.options} onSelect={handleSelectMethod} />
          )}

          {uiComponent === 'ident-fsc-form' && <IdentFscForm onSubmit={(fields) => handlePatch(fields)} />}

          {uiComponent === 'sms-enroll-form' && <SmsEnrollForm onSubmit={(phoneNumber) => handlePatch({ phoneNumber })} />}

          {uiComponent === 'tan-input-form' && (
            <TanInputForm onSubmit={(tan) => handlePatch({ tan })} error={stepData?.error} demoTan={demo?.tan} />
          )}

          {uiComponent === 'password-enroll-form' && (
            <PasswordEnrollForm onSubmit={(fields) => handlePatch(fields)} error={stepData?.error} demoPassword={demo?.password} />
          )}

          {uiComponent === 'password-login-form' && (
            <PasswordLoginForm onSubmit={(fields) => handlePatch(fields)} error={stepData?.error} demoPassword={demo?.password} />
          )}

          {uiComponent === 'email-enroll-form' && (
            <EmailEnrollForm onSubmit={(email) => handlePatch({ email })} error={stepData?.error} demoEmail={demo?.email} />
          )}

          {uiComponent === 'email-code-input-form' && (
            <EmailCodeInputForm onSubmit={(code) => handlePatch({ code })} error={stepData?.error} demoTan={demo?.tan} />
          )}

          {uiComponent === 'email-lookup-form' && (
            <EmailLookupForm onSubmit={(email) => handlePatch({ email })} error={stepData?.error} demoEmail={demo?.email} />
          )}

          {uiComponent === 'email-code-lookup-form' && (
            <EmailCodeLookupForm onSubmit={(email) => handlePatch({ email })} error={stepData?.error} demoEmail={demo?.email} />
          )}

          {uiComponent === 'email-password-lookup-form' && (
            <EmailPasswordLookupForm
              onSubmit={(fields) => handlePatch(fields)}
              error={stepData?.error}
              demoPassword={demo?.password}
              demoEmail={demo?.email}
            />
          )}

          {uiComponent === 'device-enroll-form' && activeTool && (
            <DeviceEnrollForm
              toolSessionId={activeTool.toolSessionId}
              toolId={activeTool.toolId}
              onSubmit={(body) => handlePatch(body)}
              error={stepData?.error}
            />
          )}

          {uiComponent === 'device-auth-form' && activeTool && (
            <DeviceAuthForm
              toolSessionId={activeTool.toolSessionId}
              toolId={activeTool.toolId}
              onSubmit={(body) => handlePatch(body)}
              error={stepData?.error}
            />
          )}

          {uiComponent === 'authentication-completed' && (
            <AuthenticationCompletedView
              currentAcr={currentAcr}
              currentAmr={currentAmr}
              activeMethods={activeMethods}
              demo={demo}
              onAddMethod={handleAddMethod}
              onDeactivateMethod={handleDeactivateMethod}
              onStepUp={handleStepUp}
              manageError={error || undefined}
              infoMessage={typeof stepData?.message === 'string' ? stepData.message : undefined}
            />
          )}
        </div>
      </div>

      <DebugSidebar
        channel={{ channelSessionId, channelState, currentAcr, currentAmr, activeMethods, next, stepData, demo, activeTool }}
        log={debugLog}
      />
    </div>
  )
}

export default App
