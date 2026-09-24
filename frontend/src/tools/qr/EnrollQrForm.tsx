import { t } from '../../texts'

interface EnrollQrFormProps {
  onConfirm: () => void
  error?: string
}

/**
 * `enroll-qr`'s only step - a pure opt-in, no credential to enter
 * (docs/03-tool-architektur.md): the click itself is the confirmation.
 */
export function EnrollQrForm({ onConfirm, error }: EnrollQrFormProps) {
  return (
    <div className="card">
      <h2>{t('Web-Login per QR-Code erlauben')}</h2>
      <p>
        {t(
          'Erlaubt, dass dieses Konto künftig einen Web-Login per QR-Code bestätigen kann. ' +
            'Kein zusätzliches Passwort oder Gerät nötig.',
        )}
      </p>
      {error && <div className="hint">{error}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button onClick={onConfirm}>{t('Aktivieren')}</button>
      </div>
    </div>
  )
}
