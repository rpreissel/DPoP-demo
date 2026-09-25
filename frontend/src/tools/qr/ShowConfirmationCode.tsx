import { t } from '../../texts'

interface ShowConfirmationCodeProps {
  /** Only in the response to the approval - the server keeps just a hash, so after a reload it is gone. */
  confirmationCode?: string
  onDone: () => void
  error?: string
}

/**
 * `confirm-qr-login`'s `showCode` step: the code the user types into the browser that is waiting
 * (review 2026-09, M-2). It must never be passed on - whoever types it into their browser is logged
 * in as this account.
 */
export function ShowConfirmationCode({ confirmationCode, onDone, error }: ShowConfirmationCodeProps) {
  return (
    <div className="card">
      <h2>{t('Code im Browser eingeben')}</h2>
      {confirmationCode ? (
        <p>
          <strong style={{ fontSize: '2em', letterSpacing: '0.2em' }}>{confirmationCode}</strong>
        </p>
      ) : (
        <p className="hint">{t('Der Code wird nur einmal angezeigt. Ist er verloren, starten Sie die Anmeldung per QR-Code neu.')}</p>
      )}
      <p className="hint">{t('Geben Sie diesen Code nur in das Browserfenster ein, das Sie gerade selbst vor sich haben. Geben Sie ihn niemals weiter - auch nicht auf Nachfrage.')}</p>
      {error && <div className="hint">{error}</div>}
      <div className="form-actions" style={{ marginTop: '1rem' }}>
        <button onClick={onDone}>{t('Fertig')}</button>
      </div>
    </div>
  )
}
