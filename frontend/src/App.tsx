import React, { useEffect, useState } from 'react'
import { createDpopProof, getOrCreateDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'

interface SessionStatus {
  registrationSessionId?: string
  authorisationSessionId?: string
  nextStep?: string
  identificationMeans?: string[]
}

function App() {
  const [message, setMessage] = useState<string>('Loading...')
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [sessionStatus, setSessionStatus] = useState<SessionStatus | null>(null)
  const [registrationStep, setRegistrationStep] = useState<string>('')
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

      if (status.nextStep === 'registration') {
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
        const setupResult = await setupResponse.json()
        const sessionId = setupResult.registrationSessionId as string

        const stepUrl = `${window.location.origin}/orchestrator/registration-sessions/${sessionId}/steps`
        const stepProof = await createDpopProof(keyPair.keyPair, 'POST', stepUrl)
        const stepResponse = await fetch(`/orchestrator/registration-sessions/${sessionId}/steps`, {
          method: 'POST',
          headers: { DPoP: stepProof },
        })
        if (!stepResponse.ok) {
          const body = await stepResponse.text()
          throw new Error(`Step failed: ${stepResponse.status} ${body}`)
        }
        const stepResult = await stepResponse.json()
        if (!active) return
        setRegistrationStep(stepResult.status as string)
        setSessionStatus((prev) => (prev ? { ...prev, registrationSessionId: sessionId } : null))
      }
    }

    runSessionFlow().catch((err) => setError(`Session flow error: ${err.message}`))

    return () => {
      active = false
    }
  }, [])

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
      {sessionStatus && (
        <div className="card">
          <h2>Session Status</h2>
          <pre>{JSON.stringify(sessionStatus, null, 2)}</pre>
          {registrationStep && <p>Registration step: {registrationStep}</p>}
        </div>
      )}
    </div>
  )
}

export default App
