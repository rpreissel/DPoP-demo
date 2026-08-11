import React, { useEffect, useState } from 'react'
import { createDpopProof, getOrCreateDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { SessionStatus, RegistrationSetupResult } from './types'
import { AuthenticationSetupView } from './components/AuthenticationSetupView'
import { FscForm } from './components/FscForm'
import { IdentificationForm } from './components/IdentificationForm'
import { SessionStatusView } from './components/SessionStatusView'

function App() {
  const [message, setMessage] = useState<string>('')
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [sessionStatus, setSessionStatus] = useState<SessionStatus | null>(null)
  const [error, setError] = useState<string>('')
  const [showDebug, setShowDebug] = useState(false)

  useEffect(() => {
    fetch('/orchestrator/process')
      .then((res) => res.text())
      .then((text) => setMessage(text))
      .catch((err) => setMessage(`Error: ${err.message}`))
  }, [])

  useEffect(() => {
    let active = true

    async function runSessionFlow() {
      const keyPair = await getOrCreateDpopKeyPair()
      if (!active) return
      setDpop(keyPair)

      const sessionsUrl = `${window.location.origin}/orchestrator/sessions`
      const sessionsProof = await createDpopProof(keyPair.keyPair, 'GET', sessionsUrl)
      const sessionsResponse = await fetch('/orchestrator/sessions', {
        method: 'GET',
        headers: { DPoP: sessionsProof },
      })
      if (!sessionsResponse.ok) {
        const body = await sessionsResponse.text()
        throw new Error(`Session lookup failed: ${sessionsResponse.status} ${body}`)
      }
      const status = (await sessionsResponse.json()) as SessionStatus
      if (!active) return
      setSessionStatus(status)

      if (status.next?.context === 'registration' && status.next?.step === 'registration') {
        const setupUrl = `${window.location.origin}/orchestrator/registration-sessions`
        const setupProof = await createDpopProof(keyPair.keyPair, 'POST', setupUrl)
        const setupResponse = await fetch('/orchestrator/registration-sessions', {
          method: 'POST',
          headers: { DPoP: setupProof },
        })
        if (!setupResponse.ok) {
          const body = await setupResponse.text()
          throw new Error(`Setup failed: ${setupResponse.status} ${body}`)
        }
        const setupResult = (await setupResponse.json()) as RegistrationSetupResult
        if (!active) return
        setSessionStatus({
          registrationSessionId: setupResult.registrationSessionId,
          next: setupResult.next,
        })
      }
    }

    runSessionFlow().catch((err) => setError(`Session flow error: ${err.message}`))

    return () => {
      active = false
    }
  }, [])

  async function submitIdentification(kvnr: string, name: string, vorname: string) {
    if (!dpop || !sessionStatus?.registrationSessionId) return

    const sessionId = sessionStatus.registrationSessionId
    const url = `${window.location.origin}/orchestrator/registration-sessions/${sessionId}/identification-methods/fsc`
    const proof = await createDpopProof(dpop.keyPair, 'POST', url)
    const response = await fetch(`/orchestrator/registration-sessions/${sessionId}/identification-methods/fsc`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ kvnr, name, vorname }),
    })
    if (!response.ok) {
      const body = await response.text()
      throw new Error(`Identification failed: ${response.status} ${body}`)
    }
    const result = (await response.json()) as RegistrationSetupResult
    setSessionStatus({ registrationSessionId: sessionId, next: result.next })
  }

  async function submitFsc(fsc: string) {
    if (!dpop || !sessionStatus?.registrationSessionId) return

    const sessionId = sessionStatus.registrationSessionId
    const url = `${window.location.origin}/orchestrator/registration-sessions/${sessionId}/identification-methods/fsc`
    const proof = await createDpopProof(dpop.keyPair, 'PATCH', url)
    const response = await fetch(`/orchestrator/registration-sessions/${sessionId}/identification-methods/fsc`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ fsc }),
    })
    if (!response.ok) {
      const body = await response.text()
      throw new Error(`FSC validation failed: ${response.status} ${body}`)
    }
    const result = (await response.json()) as RegistrationSetupResult
    setSessionStatus({ registrationSessionId: sessionId, next: result.next })
  }

  async function setupSmsStart(phoneNumber: string): Promise<{ smsSetupId: number; tan: string } | undefined> {
    if (!dpop || !sessionStatus?.registrationSessionId) return undefined

    const sessionId = sessionStatus.registrationSessionId
    const url = `${window.location.origin}/orchestrator/registration-sessions/${sessionId}/authentication-methods/sms`
    const proof = await createDpopProof(dpop.keyPair, 'POST', url)
    const response = await fetch(`/orchestrator/registration-sessions/${sessionId}/authentication-methods/sms`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ phoneNumber }),
    })
    if (!response.ok) {
      const body = await response.text()
      throw new Error(`SMS setup failed: ${response.status} ${body}`)
    }
    const result = (await response.json()) as RegistrationSetupResult
    setSessionStatus({ registrationSessionId: sessionId, next: result.next })

    return {
      smsSetupId: result.next.smsSetupId!,
      // In a real app the TAN would not be exposed to the frontend.
      // For this mocked demo the backend does not return the TAN, so we use a placeholder
      // and rely on the visible mock hint in the component for development/testing.
      tan: '',
    }
  }

  async function setupSmsVerify(smsSetupId: number, tan: string): Promise<boolean> {
    if (!dpop || !sessionStatus?.registrationSessionId) return false

    const sessionId = sessionStatus.registrationSessionId
    const url = `${window.location.origin}/orchestrator/registration-sessions/${sessionId}/authentication-methods/sms/verify-tan`
    const proof = await createDpopProof(dpop.keyPair, 'POST', url)
    const response = await fetch(`/orchestrator/registration-sessions/${sessionId}/authentication-methods/sms/verify-tan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: JSON.stringify({ smsSetupId, tan }),
    })
    if (!response.ok) {
      return false
    }
    const result = (await response.json()) as RegistrationSetupResult
    setSessionStatus({ registrationSessionId: sessionId, next: result.next })
    return true
  }

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

      {sessionStatus?.next?.context === 'registration' && sessionStatus?.next?.step === 'useIdentificationMethod' && (
        <IdentificationForm onSubmit={submitIdentification} />
      )}

      {sessionStatus?.next?.context === 'fsc' && sessionStatus?.next?.step === 'input' && (
        <FscForm onSubmit={submitFsc} />
      )}

      {sessionStatus?.next?.context === 'authentication' && sessionStatus?.next?.step === 'setup' && sessionStatus.next.authenticationMethods && (
        <AuthenticationSetupView
          methods={sessionStatus.next.authenticationMethods}
          onSetupSmsStart={setupSmsStart}
          onSetupSmsVerify={setupSmsVerify}
        />
      )}

      {sessionStatus?.next?.context === 'authentication' && sessionStatus?.next?.step === 'smsTanInput' && sessionStatus.next.smsSetupId && (
        <AuthenticationSetupView
          methods={['sms']}
          onSetupSmsStart={setupSmsStart}
          onSetupSmsVerify={setupSmsVerify}
        />
      )}

      <div className="debug-toggle">
        <button type="button" className="secondary" onClick={() => setShowDebug((s) => !s)}>
          {showDebug ? 'Debug-Info ausblenden' : 'Debug-Info anzeigen'}
        </button>
      </div>

      {showDebug && (
        <>
          <div className="card debug-section">
            <h2>Backend Response</h2>
            <pre>{message || 'Loading...'}</pre>
          </div>
          {dpop && (
            <div className="card debug-section">
              <h2>DPoP Public Key (JWK)</h2>
              <pre>{JSON.stringify(dpop.publicJwk, null, 2)}</pre>
            </div>
          )}
        </>
      )}
    </div>
  )
}

export default App
