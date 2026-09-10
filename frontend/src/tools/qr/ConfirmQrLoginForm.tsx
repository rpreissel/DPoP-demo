interface ConfirmQrLoginFormProps {
  verificationCode?: string
  onAccept: () => void
  onReject: () => void
  error?: string
}

/**
 * `confirm-qr-login`'s `confirm` step (docs/ideen/qr-login-ueber-app.md #5/#6) - the
 * verification code is never typed anywhere, only compared by eye against the WEB screen
 * (QR-jacking countermeasure). Only accept if it actually matches.
 */
export function ConfirmQrLoginForm({ verificationCode, onAccept, onReject, error }: ConfirmQrLoginFormProps) {
  return (
    <div className="card">
      <h2>Web-Login bestätigen?</h2>
      <p>Ein Browser möchte sich mit Ihrem Konto anmelden.</p>
      {verificationCode && (
        <div className="hint">
          Vergleichscode: <strong style={{ fontSize: '1.4em' }}>{verificationCode}</strong>
          <p style={{ marginTop: '0.5rem' }}>Bestätigen Sie nur, wenn der Browser denselben Code anzeigt.</p>
        </div>
      )}
      {error && <div className="hint">{error}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button onClick={onAccept}>Bestätigen</button>
        <button className="secondary" onClick={onReject}>
          Ablehnen
        </button>
      </div>
    </div>
  )
}
