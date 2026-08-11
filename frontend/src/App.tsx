import React, { useEffect, useState } from 'react'
import { createDpopProof, getOrCreateDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'
import type { SessionStatus, RegistrationSetupResult } from './types'
import { AuthenticationSetupView } from './components/AuthenticationSetupView'
import { FscForm } from './components/FscForm'
import { IdentificationForm } from './components/IdentificationForm'
import { SessionStatusView } from './components/SessionStatusView'

function App() {
  const [message, setMessage] = useState<string>('Loading...')
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [sessionStatus, setSessionStatus] = useState<SessionStatus | null>(null)
  const [error, setError] = useState<string>('')

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

  return (
    <div className="app">
      <h1>DPoP Demo</h1>
      <p>React {React.version} + TypeScript + Spring Boot Modulith</p>
      <div className="card">
        <h2>Backend Response</h2>
        <pre>{message}</pre>
      </div>
      {error && (
        <div className="card">
          <h2>Error</h2>
          <pre>{error}</pre>
        </div>
      )}
      {dpop && (
        <div className="card">
          <h2>DPoP Public Key (JWK)</h2>
          <pre>{JSON.stringify(dpop.publicJwk, null, 2)}</pre>
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
        <AuthenticationSetupView methods={sessionStatus.next.authenticationMethods} />
      )}
    </div>
  )
}

export default App
