import type { DpopKeyPair } from '../dpop.ts'
import type { ActiveMethodView, DemoInfo } from '../types'
import { DiagramHint } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'
import { TokenPanel } from './TokenPanel'

/** Display name for a method with no user-chosen label (singleton methods - email/sms/password). */
const DEFAULT_METHOD_LABELS: Record<string, string> = {
  sms: 'SMS',
  email: 'E-Mail',
  password: 'Passwort',
  device: 'Gerät',
}

function labelFor(method: ActiveMethodView): string {
  return method.label ?? DEFAULT_METHOD_LABELS[method.method] ?? method.method
}

/** A section heading with a hover/focus-revealed diagram of that section's journey shape - same trigger as SessionStatusView's in-progress hint. */
function SectionHeading({ text, diagram }: { text: string; diagram: keyof typeof JOURNEY_DIAGRAMS }) {
  return (
    <h3 className="section-heading">
      {text}
      <DiagramHint spec={JOURNEY_DIAGRAMS[diagram]} inline>
        <span className="diagram-hint-trigger" tabIndex={0} aria-label={`Ablauf "${text}" als Diagramm anzeigen`}>
          ℹ️
        </span>
      </DiagramHint>
    </h3>
  )
}

interface AuthenticationCompletedViewProps {
  dpop: DpopKeyPair
  channelSessionId: string
  currentAcr?: string
  currentAmr?: string[]
  /** All active methods on the account - distinct from currentAmr, which is only what THIS session proved. */
  activeMethods?: ActiveMethodView[]
  demo?: DemoInfo
  onAddMethod: () => void
  onDeactivateMethod: (methodInstanceId: string) => void
  onStepUp: (requiredAcr: string) => void
  onDeleteAccount: () => void
  manageError?: string
  infoMessage?: string
}

/** FE-11: accountId/personId come from the demo-only object, never a production field. */
export function AuthenticationCompletedView({
  dpop,
  channelSessionId,
  currentAcr,
  currentAmr,
  activeMethods,
  demo,
  onAddMethod,
  onDeactivateMethod,
  onStepUp,
  onDeleteAccount,
  manageError,
  infoMessage,
}: AuthenticationCompletedViewProps) {
  // loa2 (MFA) is the only level any tool combination in this demo can actually reach - offering
  // it as a step-up target only makes sense if the channel isn't there already.
  const canStepUpToLoa2 = currentAcr !== 'loa2'

  return (
    <>
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
          <SectionHeading text="Sicherheitsniveau erhöhen" diagram="stepUp" />
          <p>Ein Step-up fordert einen zusätzlichen Nachweis an (MFA), ohne sich neu anzumelden.</p>
          <div className="form-actions">
            <button className="secondary" onClick={() => onStepUp('loa2')}>
              Auf loa2 anheben
            </button>
          </div>
        </>
      )}

      <SectionHeading text="Anmeldeverfahren verwalten" diagram="manageMethods" />
      {manageError && <div className="hint">{manageError}</div>}
      {infoMessage && <div className="hint">{infoMessage}</div>}
      {/* activeMethods is the account's full standing method list (backend field, distinct from
          currentAmr's session-evidence scope) - a method the account has but that wasn't proven
          THIS session (e.g. logging in via sms+password alone on an account that also has email)
          still shows up here and can still be deactivated. */}
      {activeMethods && activeMethods.length > 0 && (
        <ul className="status-list">
          {activeMethods.map((method) => (
            <li key={method.id}>
              <span className="label">{labelFor(method)}</span>
              <button className="secondary" onClick={() => onDeactivateMethod(method.id)}>
                Deaktivieren
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions">
        <button onClick={onAddMethod}>Weiteres Verfahren hinzufügen</button>
      </div>

      <SectionHeading text="Account löschen" diagram="deleteAccount" />
      <p>Löscht Ihren Account und alle Anmeldemethoden endgültig.</p>
      <div className="form-actions">
        <button className="destructive" onClick={onDeleteAccount}>
          Account löschen
        </button>
      </div>
    </div>
    <TokenPanel dpop={dpop} channelSessionId={channelSessionId} />
    </>
  )
}
