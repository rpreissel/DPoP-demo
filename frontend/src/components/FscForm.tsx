import { useState } from 'react'

interface FscFormProps {
  onSubmit: (fsc: string) => void
}

export function FscForm({ onSubmit }: FscFormProps) {
  const [fsc, setFsc] = useState('')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(fsc)
  }

  return (
    <div className="card">
      <h2>Freischaltcode</h2>
      <form onSubmit={handleSubmit}>
        <label>
          FSC
          <input value={fsc} onChange={(e) => setFsc(e.target.value)} required />
        </label>
        <button type="submit">Validieren</button>
      </form>
    </div>
  )
}
