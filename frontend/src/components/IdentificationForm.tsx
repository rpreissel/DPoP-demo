import { useState } from 'react'

interface IdentificationFormProps {
  onSubmit: (kvnr: string, name: string, vorname: string) => void
}

export function IdentificationForm({ onSubmit }: IdentificationFormProps) {
  const [kvnr, setKvnr] = useState('')
  const [name, setName] = useState('')
  const [vorname, setVorname] = useState('')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(kvnr, name, vorname)
  }

  return (
    <div className="card">
      <h2>Identification</h2>
      <form onSubmit={handleSubmit}>
        <label>
          KVNR
          <input value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
        </label>
        <label>
          Name
          <input value={name} onChange={(e) => setName(e.target.value)} required />
        </label>
        <label>
          Vorname
          <input value={vorname} onChange={(e) => setVorname(e.target.value)} required />
        </label>
        <button type="submit">Weiter</button>
      </form>
    </div>
  )
}
