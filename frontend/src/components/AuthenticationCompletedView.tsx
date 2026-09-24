import { t } from '../texts'
import { Tx } from '../Tx'
import { useEffect, useState } from 'react'
import type { DpopKeyPair } from '../dpop.ts'
import type { ActiveMethodView, DemoInfo, IdTokenClaims } from '../types'
import { getIdClaims } from '../api.ts'
import { DiagramHint } from './DiagramHint'
import { Disclosure } from './Disclosure'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'
import { TokenPanel } from './TokenPanel'

/** Display name for a method with no user-chosen label (singleton methods - email/sms/password). */
const DEFAULT_METHOD_LABELS: Record<string, string> = {
  sms: t('SMS'),
  email: t('E-Mail'),
  password: t('Passwort'),
  device: t('Gerät'),
  qr: t('QR-Login'),
}

function labelFor(method: ActiveMethodView): string {
  return method.label ?? DEFAULT_METHOD_LABELS[method.method] ?? method.method
}

/** Same German factor-type names as the backend's DefaultAuthPolicy.germanFactorType. */
const FACTOR_TYPE_LABELS: Record<string, string> = {
  POSSESSION: t('Besitz'),
  KNOWLEDGE: t('Wissen'),
  INHERENCE: t('Inhärenz'),
}

function factorTypesLabel(method: ActiveMethodView): string | undefined {
  if (!method.factorTypes || method.factorTypes.length === 0) return undefined
  return method.factorTypes.map((type) => FACTOR_TYPE_LABELS[type] ?? type).join(' + ')
}

/**
 * The ADR-5 three-way cap (docs/12-entscheidungen.md), made visible instead of only discoverable
 * later as a confusing "Sicherheitsniveau nicht erreichbar" (see AuthPolicy.unreachableReason):
 * effectiveAcr is what this method actually contributes today, which can be lower than its own
 * maxAcr if it was enrolled while the session had proven less (enrolledUnderAcr).
 */
function acrDetailLabel(method: ActiveMethodView): string | undefined {
  if (!method.maxAcr) return undefined
  const capped = method.effectiveAcr && method.effectiveAcr !== method.maxAcr
  return capped
    ? t('{wirksam} (gedeckelt - max. {max}, eingerichtet unter {eingerichtet})', {
        wirksam: String(method.effectiveAcr),
        max: method.maxAcr,
        eingerichtet: String(method.enrolledUnderAcr),
      })
    : t('{wirksam} (max. {max})', { wirksam: method.effectiveAcr ?? method.maxAcr, max: method.maxAcr })
}

/** A section heading with a hover/focus-revealed diagram of that section's journey shape - same trigger as JourneyStructureView's in-progress hint. */
function SectionHeading({ text, diagram }: { text: string; diagram: keyof typeof JOURNEY_DIAGRAMS }) {
  return (
    <h3 className="section-heading">
      {text}
      <DiagramHint spec={JOURNEY_DIAGRAMS[diagram]} inline>
        <span className="diagram-hint-trigger" tabIndex={0} aria-label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: text })}>
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
  onPeerLogin: () => void
  onLogout: () => void
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
  onPeerLogin,
  onLogout,
  manageError,
  infoMessage,
}: AuthenticationCompletedViewProps) {
  // loa2 (MFA) is the only level any tool combination in this demo can actually reach - offering
  // it as a step-up target only makes sense if the channel isn't there already.
  const canStepUpToLoa2 = currentAcr !== 'loa2'

  // Who is logged in and with what claims - real ID-token claims (docs/05-api.md, "ID-Token-
  // Claims"), not the demo-only object FE-11 talks about further down. Fetched once, on demand,
  // same reasoning as the security-summary backfill (App.tsx) - not part of every response,
  // only relevant once this screen is reached. A fetch failure keeps this screen alive without
  // them (no error surfaced), same trade-off as before.
  const [claims, setClaims] = useState<IdTokenClaims | undefined>()
  useEffect(() => {
    let active = true
    getIdClaims(dpop, channelSessionId)
      .then((idClaims) => {
        if (active) setClaims(idClaims)
      })
      .catch(() => {
        // Non-fatal - the rest of this screen works fine without the claims.
      })
    return () => {
      active = false
    }
  }, [dpop, channelSessionId])

  const personName = typeof claims?.name === 'string' ? claims.name : undefined
  // The role (ADR-34): versnr = insured with us (Versicherter); personId alone = known to the
  // Personenverzeichnis but not insured here (Partner); neither = Interessent (ADR-10/18 - full
  // identity possibly attested, but no person assigned). Shown compactly in parentheses behind the
  // name, not as its own status row.
  const accountStatus = claims
    ? claims.versnr != null
      ? t('Versicherter')
      : claims.personId != null
        ? t('Partner')
        : t('Interessent')
    : undefined

  return (
    <>
    <div className="card success-card">
      <h2>{t('Authentifizierung erfolgreich!')}</h2>
      <div className="identity-row">
        <p>
          {personName ? (
            accountStatus ? (
              <Tx text="Angemeldet als {name} ({status})." name={<strong>{personName}</strong>} status={accountStatus} />
            ) : (
              <Tx text="Angemeldet als {name}." name={<strong>{personName}</strong>} />
            )
          ) : accountStatus ? (
            t('Sie sind angemeldet ({status}).', { status: accountStatus })
          ) : (
            t('Sie sind angemeldet.')
          )}
        </p>
        <button className="secondary small" onClick={onLogout}>
          {t('Abmelden')}
        </button>
      </div>
      <ul className="status-list">
        {currentAcr && (
          <li>
            <span className="label">{t('Sicherheitsniveau')}</span>
            <span className="value">{currentAcr}</span>
          </li>
        )}
        {currentAmr && currentAmr.length > 0 && (
          <li>
            <span className="label">{t('Genutzte Anmeldeverfahren')}</span>
            <span className="value">{currentAmr.join(', ')}</span>
          </li>
        )}
        {demo?.accountId != null && (
          <li>
            <span className="label">{t('Konto-ID (Demo)')}</span>
            <span className="value">{demo.accountId}</span>
          </li>
        )}
        {demo?.personId != null && (
          <li>
            <span className="label">{t('Personen-ID (Demo)')}</span>
            <span className="value">{demo.personId}</span>
          </li>
        )}
      </ul>

      {canStepUpToLoa2 && (
        <>
          <SectionHeading text={t('Sicherheitsniveau erhöhen')} diagram="stepUp" />
          <p>{t('Ein Step-up fordert einen zusätzlichen Nachweis an (MFA), ohne sich neu anzumelden.')}</p>
          <div className="form-actions">
            <button className="secondary" onClick={() => onStepUp('loa2')}>
              {t('Sicherheitsniveau jetzt erhöhen')}
            </button>
          </div>
        </>
      )}

      <SectionHeading text={t('Web-Login per QR bestätigen')} diagram="confirmPeerLogin" />
      {/* Worded as an instruction, not a status: the app cannot know whether a browser is waiting -
          the pairing code shown there is what connects the two, entered in the next step. */}
      <p>{t('Zeigt ein Browser einen QR- oder Pairing-Code an, bestätigen Sie den Login hier.')}</p>
      {activeMethods && !activeMethods.some((m) => m.method === 'qr') && (
        <p className="hint">
          {t('Dafür muss für dieses Konto das Verfahren „QR-Login“ aktiviert sein - unten unter „Anmeldeverfahren verwalten“.')}
        </p>
      )}
      <div className="form-actions">
        <button className="secondary" onClick={onPeerLogin}>
          {t('Web-Login bestätigen')}
        </button>
      </div>

      <SectionHeading text={t('Anmeldeverfahren verwalten')} diagram="manageMethods" />
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
              <span className="label">
                {labelFor(method)}
                {/* The method itself, not only the name: a user-chosen label ("Mein Handy") says
                    nothing about WHICH procedure it is, and several methods can be device-bound. */}
                <span className="method-detail-hint">
                  {[method.method, acrDetailLabel(method), factorTypesLabel(method)].filter(Boolean).join(' · ')}
                </span>
              </span>
              <button className="secondary" onClick={() => onDeactivateMethod(method.id)}>
                {t('Deaktivieren')}
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="form-actions">
        <button onClick={onAddMethod}>{t('Weiteres Verfahren hinzufügen')}</button>
      </div>

      <SectionHeading text={t('Konto löschen')} diagram="deleteAccount" />
      <p>{t('Löscht Ihr Konto und alle Anmeldeverfahren endgültig.')}</p>
      <div className="form-actions">
        <button className="destructive" onClick={onDeleteAccount}>
          {t('Konto löschen')}
        </button>
      </div>

      {/* Technical detail view, collapsed by default - same trade-off as the AccessToken claims
          in TokenPanel: the two headline facts (name + Kontostatus) live in the identity row,
          everything else only on demand. */}
      {claims && (
        <Disclosure summary={t('ID-Token-Claims')}>
          <ul className="status-list">
            {Object.entries(claims)
              .filter(([, value]) => value !== null && value !== undefined)
              .map(([key, value]) => (
                <li key={key}>
                  <span className="label">{key}</span>
                  <span className="value">{Array.isArray(value) ? value.join(', ') : String(value)}</span>
                </li>
              ))}
          </ul>
        </Disclosure>
      )}
    </div>
    <TokenPanel dpop={dpop} channelSessionId={channelSessionId} />
    </>
  )
}
