import { t } from '../../texts'

interface ConfirmQrLoginFormProps {
  onAccept: () => void
  onReject: () => void
  error?: string
}

/**
 * `confirm-qr-login`'s `confirm` step (docs/05-api.md, Peer-Login bestätigen; docs/07-betrieb.md #5).
 * Approving does not log the browser in by itself: the app then shows a code that has to be typed
 * into the browser (review 2026-09, M-2) - so approving a request you did not start yourself hands
 * nothing over.
 */
export function ConfirmQrLoginForm({ onAccept, onReject, error }: ConfirmQrLoginFormProps) {
  return (
    <div className="card">
      <h2>{t('Web-Login bestätigen?')}</h2>
      <p>{t('Ein Browser möchte sich mit Ihrem Konto anmelden.')}</p>
      <p className="hint">{t('Bestätigen Sie nur, wenn Sie die Anmeldung gerade selbst im Browser vor sich haben. Danach zeigt die App einen Code, den Sie dort eingeben.')}</p>
      {error && <div className="hint">{error}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button onClick={onAccept}>{t('Bestätigen')}</button>
        <button className="secondary" onClick={onReject}>
          {t('Ablehnen')}
        </button>
      </div>
    </div>
  )
}
