import { useState } from 'react'
import { forgetPendingPairingCode, loadPendingPairingCode } from '../../session'

interface PairingCodeInputFormProps {
  onSubmit: (pairingCode: string) => void
  error?: string
}

/**
 * `confirm-qr-login`'s `input` step (docs/05-api.md, Peer-Login bestätigen) - the pairing code is
 * either scanned/typed here manually, or pre-filled from the WEB channel's demo link
 * (App.tsx's URL-capture effect, persisted via session.ts). Consumed (forgotten) once submitted so
 * a later, unrelated confirm-qr-login run never silently reuses a stale code.
 */
export function PairingCodeInputForm({ onSubmit, error }: PairingCodeInputFormProps) {
  const [pairingCode, setPairingCode] = useState(() => loadPendingPairingCode() ?? '')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    forgetPendingPairingCode()
    // The stored/displayed form may include a grouping dash (docs/07-betrieb.md #5,
    // "XXXX-XXXX") - the actual pairingCode value never contains one.
    onSubmit(pairingCode.replace(/[^a-zA-Z0-9]/g, '').toUpperCase())
  }

  return (
    <div className="card">
      <h2>Web-Login per QR bestätigen</h2>
      <p>Geben Sie den Pairing-Code von der Web-Seite ein, oder scannen Sie deren QR-Code.</p>
      {error && <div className="hint">{error}</div>}
      <form onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="pairingCode">Pairing-Code</label>
          <input
            id="pairingCode"
            value={pairingCode}
            onChange={(e) => setPairingCode(e.target.value)}
            placeholder="z. B. AB3D-7KQ2"
            required
            autoFocus
          />
        </div>
        <div className="form-actions">
          <button type="submit">Weiter</button>
        </div>
      </form>
    </div>
  )
}
