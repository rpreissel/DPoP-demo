import React, { useEffect, useState } from 'react'
import { createDpopProof, getOrCreateDpopKeyPair, type DpopKeyPair } from './dpop.ts'
import './App.css'

function App() {
  const [message, setMessage] = useState<string>('Loading...')
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [proof, setProof] = useState<string>('')
  const [error, setError] = useState<string>('')

  useEffect(() => {
    fetch('/orchestrator/process')
      .then((res) => res.text())
      .then((text) => setMessage(text))
      .catch((err) => setMessage(`Error: ${err.message}`))
  }, [])

  useEffect(() => {
    getOrCreateDpopKeyPair()
      .then(async (keyPair) => {
        setDpop(keyPair)
        const demoProof = await createDpopProof(
          keyPair.keyPair,
          'GET',
          `${window.location.origin}/orchestrator/process`,
        )
        setProof(demoProof)
      })
      .catch((err) => setError(`DPoP error: ${err.message}`))
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
      {proof && (
        <div className="card">
          <h2>DPoP Proof (Demo)</h2>
          <pre>{proof}</pre>
        </div>
      )}
    </div>
  )
}

export default App
