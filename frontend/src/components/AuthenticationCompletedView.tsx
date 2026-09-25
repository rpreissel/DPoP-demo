import { t } from '../texts'
import { useEffect, useState } from 'react'
import type { DpopKeyPair } from '../dpop.ts'
import type { ActiveMethodView, DemoInfo, IdTokenClaims } from '../types'
import { getIdClaims } from '../api.ts'
import { DiagramHint } from './DiagramHint'
import { Demo } from './DemoArea'
import { Disclosure } from './Disclosure'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'
import { TokenPanel } from './TokenPanel'
import { isBelowAcr } from '../acr'
import { accountRole } from '../accountRole'

/** Display name for a method with no user-chosen label (singleton methods - email/sms/password). */
const DEFAULT_METHOD_LABELS: Record<string, string> = {
  sms: t('SMS'),
  email: t('E-Mail'),
  password: t('Passwort'),
  device: t('Gerät'),
  qr: t('QR-Login'),
  kobil: t('KOBIL'),
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

/**
 * A section heading; the diagram of that section's journey shape goes to the demo column next to
 * the phone (DemoArea) - a real app shows the heading, not how the orchestrator runs it.
 */
function SectionHeading({ text, diagram }: { text: string; diagram: keyof typeof JOURNEY_DIAGRAMS }) {
  return (
    <>
      <h3 className="section-heading">{text}</h3>
      <Demo>
        <p className="demo-diagram">
          {text}
          <DiagramHint spec={JOURNEY_DIAGRAMS[diagram]} inline>
            <span className="diagram-hint-trigger" tabIndex={0} aria-label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: text })}>
              ℹ️
            </span>
          </DiagramHint>
        </p>
      </Demo>
    </>
  )
}

/** The three screens of the logged-in app: a welcome, the person's data, the account's security. */
export type AccountView = 'home' | 'profile' | 'security'

interface AuthenticationCompletedViewProps {
  dpop: DpopKeyPair
  channelSessionId: string
  currentAcr?: string
  currentAmr?: string[]
  /** All active methods on the account - distinct from currentAmr, which is only what THIS session proved. */
  activeMethods?: ActiveMethodView[]
  demo?: DemoInfo
  /**
   * Which screen shows - held by the caller, so a step-up or an added method returns to the
   * screen it was started from rather than to the welcome (this view unmounts meanwhile).
   */
  view: AccountView
  onNavigate: (view: AccountView) => void
  onAddMethod: () => void
  onDeactivateMethod: (methodInstanceId: string) => void
  onStepUp: (requiredAcr: string) => void
  onDeleteAccount: () => void
  onPeerLogin: () => void
  onLogout: () => void
  manageError?: string
  infoMessage?: string
}

/** A list row like the method choice: icon tile, bold label, grey hint, chevron. */
function MenuRow({ icon, label, hint, onClick }: { icon: string; label: string; hint?: string; onClick: () => void }) {
  return (
    <li>
      <button className="method-choice" onClick={onClick}>
        <span className="method-choice-icon" aria-hidden="true">
          {icon}
        </span>
        <span className="method-choice-text">
          <span className="method-choice-label">{label}</span>
          {hint && <span className="method-choice-hint">{hint}</span>}
        </span>
      </button>
    </li>
  )
}

/** Head of a sub-screen: back to the welcome, then the screen's title. */
function SubScreenHead({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <>
      <button className="account-back" onClick={onBack}>
        {t('Zurück')}
      </button>
      <h2>{title}</h2>
    </>
  )
}

function StatusRow({ label, value }: { label: string; value?: string }) {
  if (value == null || value === '') return null
  return (
    <li>
      <span className="label">{label}</span>
      <span className="value">{value}</span>
    </li>
  )
}

/** FE-11: accountId/personId come from the demo-only object, never a production field. */
export function AuthenticationCompletedView({
  dpop,
  channelSessionId,
  currentAcr,
  currentAmr,
  activeMethods,
  demo,
  view,
  onNavigate,
  onAddMethod,
  onDeactivateMethod,
  onStepUp,
  onDeleteAccount,
  onPeerLogin,
  onLogout,
  manageError,
  infoMessage,
}: AuthenticationCompletedViewProps) {
  // loa2 is the step-up target the demo offers; loa3 comes only from an identification (eID,
  // Nect), never from a step-up - so offering it only makes sense below loa2.
  const canStepUpToLoa2 = isBelowAcr(currentAcr, 'loa2')

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
  // identity possibly attested, but no person assigned).
  const accountStatus = claims ? accountRole(claims.personId, claims.versnr) : undefined
  const hasQrLogin = activeMethods?.some((m) => m.method === 'qr') ?? true

  const home = (
    <>
      <div className="card success-card">
        <h2>{personName ? t('Willkommen, {name}!', { name: personName }) : t('Willkommen!')}</h2>
        <p>{accountStatus ? t('Sie sind als {status} angemeldet.', { status: accountStatus }) : t('Sie sind angemeldet.')}</p>
      </div>
      <ul className="method-choice-list account-menu">
        <MenuRow icon="👤" label={t('Profil')} hint={t('Ihre persönlichen Daten')} onClick={() => onNavigate('profile')} />
        <MenuRow icon="🔒" label={t('Sicherheit')} hint={t('Anmeldeverfahren und Konto')} onClick={() => onNavigate('security')} />
        {/* Worded as an instruction, not a status: the app cannot know whether a browser is
            waiting - the pairing code shown there is what connects the two, entered next. */}
        <MenuRow
          icon="💻"
          label={t('Anmeldung im Browser bestätigen')}
          hint={hasQrLogin ? t('Code aus dem Browser eingeben') : t('Dafür zuerst unter „Sicherheit“ den QR-Login hinzufügen')}
          onClick={onPeerLogin}
        />
      </ul>
      <Demo>
        <p className="demo-diagram">
          {t('Anmeldung im Browser bestätigen')}
          <DiagramHint spec={JOURNEY_DIAGRAMS.confirmPeerLogin} inline>
            <span
              className="diagram-hint-trigger"
              tabIndex={0}
              aria-label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: t('Anmeldung im Browser bestätigen') })}
            >
              ℹ️
            </span>
          </DiagramHint>
        </p>
      </Demo>
      <div className="form-actions">
        <button className="secondary" onClick={onLogout}>
          {t('Abmelden')}
        </button>
      </div>
    </>
  )

  const profile = (
    <div className="card">
      <SubScreenHead title={t('Profil')} onBack={() => onNavigate('home')} />
      <ul className="status-list">
        <StatusRow label={t('Vor- und Nachname')} value={personName} />
        <StatusRow label={t('Status')} value={accountStatus} />
        <StatusRow label={t('Versichertennummer')} value={claims?.versnr} />
        <StatusRow label={t('Partnernummer')} value={claims?.personId} />
        <StatusRow label={t('E-Mail')} value={claims?.email} />
      </ul>
      {!claims && <p className="hint">{t('Ihre Daten werden geladen …')}</p>}
      {canStepUpToLoa2 && (
        <>
          <SectionHeading text={t('Sicherheitsniveau erhöhen')} diagram="stepUp" />
          <p>{t('Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren, dann erreichen Sie Sicherheitsniveau 2 - ohne sich neu anzumelden.')}</p>
          <div className="form-actions">
            <button className="secondary" onClick={() => onStepUp('loa2')}>
              {t('Sicherheitsniveau 2 anfordern')}
            </button>
          </div>
        </>
      )}
      {(demo?.accountId != null || demo?.personId != null) && (
        <Demo>
          <ul className="status-list">
            <StatusRow label={t('Konto-ID (Demo)')} value={demo?.accountId != null ? String(demo.accountId) : undefined} />
            <StatusRow label={t('Personen-ID (Demo)')} value={demo?.personId != null ? String(demo.personId) : undefined} />
          </ul>
        </Demo>
      )}
      {/* The raw claims next to the phone - the screen itself shows them as a person's data. */}
      {claims && (
        <Demo>
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
        </Demo>
      )}
    </div>
  )

  const security = (
    <div className="card">
      <SubScreenHead title={t('Sicherheit')} onBack={() => onNavigate('home')} />
      <ul className="status-list">
        <StatusRow label={t('Sicherheitsniveau')} value={currentAcr} />
        <StatusRow label={t('Genutzte Anmeldeverfahren')} value={currentAmr && currentAmr.length > 0 ? currentAmr.join(', ') : undefined} />
      </ul>

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
              <span className="label">{labelFor(method)}</span>
              <button className="secondary small" onClick={() => onDeactivateMethod(method.id)}>
                {t('Deaktivieren')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {/* The method itself, not only the name: a user-chosen label ("Mein Handy") says nothing
          about WHICH procedure it is, and several methods can be device-bound - shown next to the
          phone, it is how the orchestrator sees each one. */}
      {activeMethods && activeMethods.length > 0 && (
        <Demo>
          <ul className="status-list">
            {activeMethods.map((method) => (
              <li key={method.id}>
                <span className="label">{labelFor(method)}</span>
                <span className="value method-detail-hint">
                  {[method.method, acrDetailLabel(method), factorTypesLabel(method)].filter(Boolean).join(' · ')}
                </span>
              </li>
            ))}
          </ul>
        </Demo>
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
    </div>
  )

  return (
    <>
      {view === 'profile' ? profile : view === 'security' ? security : home}
      <Demo>
        <TokenPanel dpop={dpop} channelSessionId={channelSessionId} />
      </Demo>
    </>
  )
}
