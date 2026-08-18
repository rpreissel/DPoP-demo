import React, { useEffect, useState } from 'react'
import { createDpopProof, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { SessionStatus, OrchestratorResponse } from './types'
import { getUIComponent, getAvailableMethods } from './routing.ts'
import { AuthenticationSetupView } from './components/AuthenticationSetupView'
import { FscForm } from './components/FscForm'
import { IdentificationForm } from './components/IdentificationForm'
import { SessionStatusView } from './components/SessionStatusView'

function App() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [sessionStatus, setSessionStatus] = useState<SessionStatus | null>(null)
  const [error, setError] = useState<string>('')
  const [showDebug, setShowDebug] = useState(false)

  // Initialize channel session
  useEffect(() => {
    let active = true

    async function initializeChannel() {
      const keyPair = await getOrCreateDpopKeyPair()
      if (!active) return
      setDpop(keyPair)

      const channelUrl = `${window.location.origin}/orchestrator/api/v1/app/channels`
      const proof = await createDpopProof(keyPair.keyPair, 'POST', channelUrl)
      const response = await fetch('/orchestrator/api/v1/app/channels', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          DPoP: proof,
        },
      })

      if (!response.ok) {
        const body = await response.text()
        throw new Error(`Channel initialization failed: ${response.status} ${body}`)
      }

      const result = (await response.json()) as OrchestratorResponse
      if (!active) return

      setSessionStatus({
        channelSessionId: result.channelSessionId,
        next: result.next,
        processState: result.processState,
        attemptState: result.attemptState,
      })
    }

    initializeChannel().catch((err) => setError(`Init error: ${err.message}`))

    return () => {
      active = false
    }
  }, [])

  async function handleReset() {
    try {
      setError('')
      setSessionStatus(null)
      await resetDpopKeyPair()
      // Re-trigger initialization
      const keyPair = await getOrCreateDpopKeyPair()
      setDpop(keyPair)

      const channelUrl = `${window.location.origin}/orchestrator/api/v1/app/channels`
      const proof = await createDpopProof(keyPair.keyPair, 'POST', channelUrl)
      const response = await fetch('/orchestrator/api/v1/app/channels', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', DPoP: proof },
      })

      const result = (await response.json()) as OrchestratorResponse
      setSessionStatus({
        channelSessionId: result.channelSessionId,
        next: result.next,
        processState: result.processState,
        attemptState: result.attemptState,
      })
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      setError(`Reset failed: ${message}`)
    }
  }

  // Route based on context/step, not URL
  const uiComponent = getUIComponent(sessionStatus?.next)
  const availableMethods = getAvailableMethods(sessionStatus?.next)
  const resultJson = sessionStatus?.attemptState?.result
    ? JSON.stringify(sessionStatus.attemptState.result as unknown, null, 2)
    : ''

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

      {sessionStatus && <SessionStatusView status={sessionStatus} />}

      <div className="controls">
        <button onClick={handleReset} className="button-reset">
          Reset & Restart
        </button>
        <button onClick={() => setShowDebug(!showDebug)} className="button-debug">
          {showDebug ? 'Hide Debug' : 'Show Debug'}
        </button>
      </div>

      {showDebug && sessionStatus && (
        <div className="card debug-card">
          <h3>Debug Info</h3>
          <pre>{JSON.stringify(sessionStatus, null, 2)}</pre>
          <p>UI Component: {uiComponent || 'none'}</p>
          <p>Available Methods: {availableMethods.join(', ') || 'none'}</p>
        </div>
      )}

      {/* UI components rendered based on routing table, not URLs */}
      {uiComponent === 'identification-method-selection' && (
        <IdentificationForm onSubmit={submitIdentification} />
      )}

      {uiComponent === 'fsc-form' && <FscForm onSubmit={submitFsc} />}

      {uiComponent === 'authentication-method-selection' && (
        <AuthenticationSetupView
          onSubmit={setupAuthentication}
          methods={availableMethods}
          mode={sessionStatus?.next?.context === 'enrollment' ? 'enroll' : 'use'}
        />
      )}

      {uiComponent === 'authentication-completed' && (
        <div className="card success-card">
          <h2>Authentifizierung erfolgreich!</h2>
          <p>Sie sind angemeldet.</p>
          {resultJson && <pre>{resultJson}</pre>}
        </div>
      )}
    </div>
  )

  // Handlers for form submissions
  async function submitIdentification(kvnr: string, name: string, vorname: string) {
    if (!dpop || !sessionStatus?.channelSessionId) return

    const url = `${window.location.origin}/orchestrator/api/v1/app/channels/${sessionStatus.channelSessionId}/identification-methods/fsc/attempts`
    const proof = await createDpopProof(dpop.keyPair, 'POST', url)
    const response = await fetch(`/orchestrator/api/v1/app/channels/${sessionStatus.channelSessionId}/identification-methods/fsc/attempts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ kvnr, name, vorname }),
    })

    if (!response.ok) {
      const body = await response.text()
      throw new Error(`Identification start failed: ${response.status} ${body}`)
    }

    const result = (await response.json()) as OrchestratorResponse
    setSessionStatus({
      channelSessionId: result.channelSessionId || sessionStatus.channelSessionId,
      next: result.next,
      processState: result.processState,
      attemptState: result.attemptState,
    })
  }

  async function submitFsc(fsc: string) {
    if (!dpop || !sessionStatus?.attemptState?.attemptId) return

    const attemptId = sessionStatus.attemptState.attemptId
    const url = `${window.location.origin}/orchestrator/api/v1/identification-methods/fsc/attempts/${attemptId}`
    const proof = await createDpopProof(dpop.keyPair, 'PATCH', url)
    const response = await fetch(`/orchestrator/api/v1/identification-methods/fsc/attempts/${attemptId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ fsc }),
    })

    if (!response.ok) {
      const body = await response.text()
      throw new Error(`FSC validation failed: ${response.status} ${body}`)
    }

    const result = (await response.json()) as OrchestratorResponse
    setSessionStatus({
      channelSessionId: result.channelSessionId || sessionStatus.channelSessionId,
      next: result.next,
      processState: result.processState,
      attemptState: result.attemptState,
    })
  }

  async function setupAuthentication(method: string, mode: string, data?: Record<string, unknown>) {
    if (!dpop || !sessionStatus?.channelSessionId) return

    const url = `${window.location.origin}/orchestrator/api/v1/app/channels/${sessionStatus.channelSessionId}/authentication-methods/${method}/${mode}/attempts`
    const proof = await createDpopProof(dpop.keyPair, 'POST', url)
    const response = await fetch(`/orchestrator/api/v1/app/channels/${sessionStatus.channelSessionId}/authentication-methods/${method}/${mode}/attempts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify(data || {}),
    })

    if (!response.ok) {
      const body = await response.text()
      throw new Error(`Authentication setup failed: ${response.status} ${body}`)
    }

    const result = (await response.json()) as OrchestratorResponse
    setSessionStatus({
      channelSessionId: result.channelSessionId || sessionStatus.channelSessionId,
      next: result.next,
      processState: result.processState,
      attemptState: result.attemptState,
    })
  }
}

export default App
