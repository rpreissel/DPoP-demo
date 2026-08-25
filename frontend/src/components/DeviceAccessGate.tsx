interface DeviceAccessGateProps {
  onConfirm: (accessMeans: 'pin' | 'biometric') => void
  busy?: boolean
}

/**
 * Mocks the system PIN/biometric prompt that gates use of the device's private key (real devices:
 * a platform authenticator's user-verification step). Biometric is the primary CTA with a PIN
 * fallback link, mirroring how Face ID/Touch ID prompts behave on real devices - which of the two
 * was used is decided HERE, per attempt, not fixed at enrollment (docs/03-tool-architektur.md):
 * WebAuthn itself never tells the relying party which modality was used, only that verification
 * succeeded.
 */
export function DeviceAccessGate({ onConfirm, busy }: DeviceAccessGateProps) {
  return (
    <div className="card">
      <h2>Gerät entsperren</h2>
      <p>Bestätigen Sie den Zugriff auf den geräteeigenen Schlüssel.</p>
      <div className="hint">
        <strong>Demo-Modus:</strong> PIN/Biometrie werden hier nur simuliert, keine echte
        Systemabfrage.
      </div>
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button type="button" disabled={busy} onClick={() => onConfirm('biometric')}>
          Mit Biometrie bestätigen
        </button>
      </div>
      <p style={{ marginTop: '0.75rem' }}>
        <button type="button" className="secondary" disabled={busy} onClick={() => onConfirm('pin')}>
          Stattdessen PIN verwenden
        </button>
      </p>
    </div>
  )
}
