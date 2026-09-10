interface EnrollQrFormProps {
  onConfirm: () => void
  error?: string
}

/**
 * `enroll-qr`'s only step - a pure opt-in, no credential to enter
 * (docs/ideen/qr-login-ueber-app.md #5): the click itself is the confirmation.
 */
export function EnrollQrForm({ onConfirm, error }: EnrollQrFormProps) {
  return (
    <div className="card">
      <h2>Web-Login per QR erlauben</h2>
      <p>
        Erlaubt, dass dieses Konto künftig einen Web-Login per QR-Code bestätigen kann
        (docs/ideen/qr-login-ueber-app.md). Kein zusätzliches Passwort oder Gerät nötig.
      </p>
      {error && <div className="hint">{error}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button onClick={onConfirm}>Aktivieren</button>
      </div>
    </div>
  )
}
