import { useState } from 'react'
import { activate } from '../../kobilSdk'
import { storeUnlockSecret } from '../../kobilUnlockSecret'

interface KobilEnrollFormProps {
  tenantId?: string
  kobilUserId?: string
  activationCode?: string
  pin?: string
  unlockSecret?: string
  onSubmit: (body: Record<string, unknown>) => void
  error?: string
}

/**
 * enroll-kobil/activate - names the device, runs the (mocked) MC SDK activation against KOBIL, and
 * stores the unlock secret locally behind the chosen access means.
 *
 * The PIN is visible to this component because the SDK's activation call takes it, and to nobody
 * else: it is never shown to the user and never kept after the call.
 */
export function KobilEnrollForm({
  tenantId,
  kobilUserId,
  activationCode,
  pin,
  unlockSecret,
  onSubmit,
  error,
}: KobilEnrollFormProps) {
  const [label, setLabel] = useState('')
  const [namingDone, setNamingDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [sdkError, setSdkError] = useState<string>()

  const ready = tenantId !== undefined && kobilUserId !== undefined && activationCode !== undefined && pin !== undefined

  async function handleConfirm(biometricConsent: boolean) {
    if (!ready) return
    setBusy(true)
    setSdkError(undefined)
    try {
      await activate({ tenantId, userId: kobilUserId }, activationCode, pin)
      // Only on consent, and only then does the server keep its counterpart: declining leaves
      // nothing behind on either side, so the choice has a consequence instead of being a label.
      if (biometricConsent && unlockSecret) storeUnlockSecret(kobilUserId, unlockSecret)
      onSubmit({ activated: true, biometricConsent, label: label.trim() || 'Mein Handy' })
    } catch (err) {
      setSdkError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  if (!namingDone) {
    return (
      <div className="card">
        <h2>Gerät bei KOBIL registrieren</h2>
        <p>
          Vergeben Sie einen Namen, um dieses Gerät später wiederzuerkennen (z.&nbsp;B. „Diensthandy“).
        </p>
        {error && <div className="hint">{error}</div>}
        <form
          onSubmit={(event) => {
            event.preventDefault()
            setNamingDone(true)
          }}
          className="form-grid"
          style={{ marginTop: '1rem' }}
        >
          <div className="form-group">
            <label htmlFor="kobil-label">Gerätename</label>
            <input
              id="kobil-label"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Mein Handy"
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

  return (
    <div className="card">
      <h2>Biometrie erlauben?</h2>
      <p>
        Mit Ihrem Passwort können Sie dieses Gerät immer entsperren. Zusätzlich können Sie
        Biometrie erlauben – dann hinterlegt die App dafür ein Geräteheimnis.
      </p>
      <div className="hint">
        <strong>Demo-Modus:</strong> Biometrie wird nur simuliert. Die KOBIL-PIN kennt allein das
        Backend – sie wird Ihnen nie angezeigt und nicht von Ihnen vergeben.
      </div>
      {(error || sdkError) && <div className="hint">{error ?? sdkError}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button type="button" disabled={busy || !ready} onClick={() => handleConfirm(true)}>
          Biometrie erlauben
        </button>
      </div>
      <p style={{ marginTop: '0.75rem' }}>
        <button
          type="button"
          className="secondary"
          disabled={busy || !ready}
          onClick={() => handleConfirm(false)}
        >
          Nur mit Passwort
        </button>
      </p>
    </div>
  )
}
