import React, { useEffect, useState } from 'react'
import './App.css'

function App() {
  const [message, setMessage] = useState<string>('Loading...')

  useEffect(() => {
    fetch('/orchestrator/process')
      .then((res) => res.text())
      .then((text) => setMessage(text))
      .catch((err) => setMessage(`Error: ${err.message}`))
  }, [])

  return (
    <div className="app">
      <h1>DPoP Demo</h1>
      <p>React {React.version} + TypeScript + Spring Boot Modulith</p>
      <div className="card">
        <h2>Backend Response</h2>
        <pre>{message}</pre>
      </div>
    </div>
  )
}

export default App
