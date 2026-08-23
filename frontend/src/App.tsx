import React, { useEffect, useRef, useState } from 'react'
import { getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { DemoInfo, Next, StepData } from './types'
import { getUIComponent } from './routing.ts'
import { activateTool, ApiError, cancelProcess, createChannel, getChannel, patchTool } from './api.ts'
import { AuthenticationCompletedView } from './components/AuthenticationCompletedView'
import { IdentFscForm } from './components/IdentFscForm'
import { SelectMethodView } from './components/SelectMethodView'
import { SessionStatusView } from './components/SessionStatusView'
import { SmsEnrollForm } from './components/SmsEnrollForm'
import { TanInputForm } from './components/TanInputForm'

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
    const hint = err.status === 410 ? ' Bitte "Reset & Restart" klicken, um neu zu starten.' : ''
    return `${prefix}: ${err.message}${hint}`
  }
  return `${prefix}: ${err instanceof Error ? err.message : String(err)}`
}

function App() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [channelSessionId, setChannelSessionId] = useState<string | undefined>()
  const [channelState, setChannelState] = useState<string | undefined>()
  const [currentAcr, setCurrentAcr] = useState<string | undefined>()
  const [currentAmr, setCurrentAmr] = useState<string[] | undefined>()
  const [next, setNext] = useState<Next | undefined>()
  const [stepData, setStepData] = useState<StepData | undefined>()
  const [demo, setDemo] = useState<DemoInfo | undefined>()
  const [activeTool, setActiveTool] = useState<ActiveTool | null>(null)
  const [error, setError] = useState('')
  const [showDebug, setShowDebug] = useState(false)

  function applyChannelResponse(response: {
    channelSessionId: string
    state: string
    currentAcr?: string
    currentAmr?: string[]
    stepData?: StepData
    next?: Next
  }) {
    setChannelSessionId(response.channelSessionId)
    setChannelState(response.state)
    setCurrentAcr(response.currentAcr)
    setCurrentAmr(response.currentAmr)
    setNext(response.next)
    setStepData(response.stepData)
    setActiveTool(null)
  }

  /**
   * `response.toolSessionId` is always the tool that was just acted on (`actedToolId`) - NOT
   * necessarily `response.next.toolId`. A Completed outcome can hand off straight to a different,
   * not-yet-activated tool (e.g. ident-fsc -> auth-sms on single-candidate skip); pairing that
   * new toolId with the old toolSessionId would make the auto-activate effect below think the new
   * tool is already active and never actually activate it (no request ever fires, no TAN issued).
   */
  async function applyToolResponse(
    keyPair: DpopKeyPair,
    channelId: string,
    response: { toolSessionId: string; stepData?: StepData; next: Next; demo?: DemoInfo },
    actedToolId: string
  ) {
    setNext(response.next)
    setStepData(response.stepData)
    setDemo(response.demo)

    if (response.next.type === 'tool' && response.next.toolId === actedToolId) {
      setActiveTool({ toolSessionId: response.toolSessionId, toolId: response.next.toolId })
      return
    }

    setActiveTool(null)
    if (response.next.type === 'flow' && response.next.context === 'authentication' && response.next.step === 'authenticated') {
      // Tool responses don't carry channel-level info (currentAcr/currentAmr/state) - refresh it.
      const channel = await getChannel(keyPair, channelId)
      applyChannelResponse(channel)
    }
  }

  // Bootstrap: DPoP key + channel init.
  useEffect(() => {
    let active = true
    async function init() {
      const keyPair = await getOrCreateDpopKeyPair()
      if (!active) return
      setDpop(keyPair)
      const response = await createChannel(keyPair)
      if (!active) return
      applyChannelResponse(response)
    }
    init().catch((err) => setError(describeError('Init error', err)))
    return () => {
      active = false
    }
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

    const toolId = next.toolId
    activatingToolIdRef.current = toolId
    activateTool(dpop, channelSessionId, toolId)
      .then((response) => applyToolResponse(dpop, channelSessionId, response, toolId))
      .catch((err) => setError(describeError('Tool activation failed', err)))
      .finally(() => {
        if (activatingToolIdRef.current === toolId) activatingToolIdRef.current = null
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, activeTool])

  async function handleReset() {
    try {
      setError('')
      setChannelSessionId(undefined)
      setChannelState(undefined)
      setNext(undefined)
      setStepData(undefined)
      setDemo(undefined)
      setActiveTool(null)
      await resetDpopKeyPair()
      const keyPair = await getOrCreateDpopKeyPair()
      setDpop(keyPair)
      const response = await createChannel(keyPair)
      applyChannelResponse(response)
    } catch (err) {
      setError(describeError('Reset failed', err))
    }
  }

  async function handleCancel() {
    if (!dpop || !channelSessionId) return
    try {
      const response = await cancelProcess(dpop, channelSessionId)
      applyChannelResponse(response)
    } catch (err) {
      setError(describeError('Cancel failed', err))
    }
  }

  async function handleSelectMethod(toolId: string) {
    if (!dpop || !channelSessionId) return
    try {
      const response = await activateTool(dpop, channelSessionId, toolId)
      await applyToolResponse(dpop, channelSessionId, response, toolId)
    } catch (err) {
      setError(describeError('Tool activation failed', err))
    }
  }

  async function handlePatch(body: Record<string, unknown>) {
    if (!dpop || !channelSessionId || !activeTool) return
    try {
      const response = await patchTool(dpop, activeTool.toolSessionId, activeTool.toolId, body)
      await applyToolResponse(dpop, channelSessionId, response, activeTool.toolId)
    } catch (err) {
      setError(describeError('Request failed', err))
    }
  }

  const uiComponent = getUIComponent(next)
  // Nothing to cancel before a process even started, or once it's already finished.
  const canCancel = !!next && !(next.type === 'flow' && next.context === 'authentication' && next.step === 'authenticated')

  return (
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

      <SessionStatusView channelSessionId={channelSessionId} state={channelState} next={next} />

      <div className="controls" style={{ marginTop: '1.5rem', display: 'flex', gap: '0.75rem' }}>
        {canCancel && (
          <button className="secondary" onClick={handleCancel}>
            Abbrechen
          </button>
        )}
        <button className="secondary" onClick={handleReset}>
          Reset &amp; Restart
        </button>
        <button className="secondary" onClick={() => setShowDebug(!showDebug)}>
          {showDebug ? 'Debug ausblenden' : 'Debug anzeigen'}
        </button>
      </div>

      {showDebug && (
        <div className="card debug-section">
          <h3>Debug Info</h3>
          <pre>
            {JSON.stringify({ channelSessionId, channelState, currentAcr, currentAmr, next, stepData, demo, activeTool }, null, 2)}
          </pre>
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

      {uiComponent === 'authentication-completed' && (
        <AuthenticationCompletedView currentAcr={currentAcr} currentAmr={currentAmr} demo={demo} />
      )}
    </div>
  )
}

export default App
