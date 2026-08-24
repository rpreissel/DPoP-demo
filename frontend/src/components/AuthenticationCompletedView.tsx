import type { DemoInfo } from '../types'

interface AuthenticationCompletedViewProps {
  currentAcr?: string
  currentAmr?: string[]
  /** All active methods on the account - distinct from currentAmr, which is only what THIS session proved. */
  activeMethods?: string[]
  demo?: DemoInfo
  onAddMethod: () => void
  onDeactivateMethod: (method: string) => void
  onStepUp: (requiredAcr: string) => void
  manageError?: string
  infoMessage?: string
}

/** FE-11: accountId/personId come from the demo-only object, never a production field. */
export function AuthenticationCompletedView({
  currentAcr,
  currentAmr,
  activeMethods,
  demo,
  onAddMethod,
  onDeactivateMethod,
  onStepUp,
  manageError,
  infoMessage,
}: AuthenticationCompletedViewProps) {
  // loa2 (MFA) is the only level any tool combination in this demo can actually reach - offering
  // it as a step-up target only makes sense if the channel isn't there already.
  const canStepUpToLoa2 = currentAcr !== 'loa2'

  return (
    <div className="card success-card">
      <h2>Authentifizierung erfolgreich!</h2>
      <p>Sie sind angemeldet.</p>
      <ul className="status-list">
        {currentAcr && (
          <li>
            <span className="label">Sicherheitsniveau (ACR)</span>
            <span className="value">{currentAcr}</span>
          </li>
        )}
        {currentAmr && currentAmr.length > 0 && (
          <li>
            <span className="label">Nachgewiesene Methoden (AMR)</span>
            <span className="value">{currentAmr.join(', ')}</span>
          </li>
        )}
        {demo?.accountId != null && (
          <li>
            <span className="label">Account-ID (Demo)</span>
            <span className="value">{demo.accountId}</span>
          </li>
        )}
        {demo?.personId != null && (
          <li>
            <span className="label">Person-ID (Demo)</span>
            <span className="value">{demo.personId}</span>
          </li>
        )}
      </ul>

      {canStepUpToLoa2 && (
        <>
          <h3 className="section-heading">Sicherheitsniveau erhöhen</h3>
          <p>Ein Step-up fordert einen zusätzlichen Nachweis an (MFA), ohne sich neu anzumelden.</p>
          <div className="form-actions">
            <button className="secondary" onClick={() => onStepUp('loa2')}>
              Auf loa2 anheben
            </button>
          </div>
        </>
      )}

      <h3 className="section-heading">Anmeldeverfahren verwalten</h3>
      {manageError && <div className="hint">{manageError}</div>}
      {infoMessage && <div className="hint">{infoMessage}</div>}
      {/* activeMethods is the account's full standing method list (backend field, distinct from
          currentAmr's session-evidence scope) - a method the account has but that wasn't proven
          THIS session (e.g. logging in via sms+password alone on an account that also has email)
          still shows up here and can still be deactivated. */}
      {activeMethods && activeMethods.length > 0 && (
        <ul className="status-list">
          {activeMethods.map((method) => (
            <li key={method}>
              <span className="label">{method}</span>
              <button className="secondary" onClick={() => onDeactivateMethod(method)}>
                Deaktivieren
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions">
        <button onClick={onAddMethod}>Weiteres Verfahren hinzufügen</button>
      </div>
    </div>
  )
}
