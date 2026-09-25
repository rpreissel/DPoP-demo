import type { JourneyDiagramCurrentStep, JourneyDiagramSpec } from './components/JourneyDiagram'
import type { JourneyDebugStep } from './types'
import { t } from './texts'

/**
 * The representative shape of each entry journey, shared between the start-screen hover previews
 * and the in-progress hint (JourneyStructureView) so both describe the same journey the same way.
 * Deliberately a SHAPE, not a spec - the backend decides the real order case by case
 * (docs/04-orchestrierung.md); e.g. registration can chain more than one factor before email
 * confirmation. Only the one branch each argument actually hinges on is drawn.
 */
export const JOURNEY_DIAGRAMS: Record<
  | 'channel'
  | 'auto'
  | 'register'
  | 'registerEnrollFirst'
  | 'login'
  | 'stepUp'
  | 'manageMethods'
  | 'deleteAccount'
  | 'reIdentify'
  | 'confirmPeerLogin'
  | 'webLoginLoa1'
  | 'webLoginLoa2'
  | 'webLoginQrTest',
  JourneyDiagramSpec
> = {
  channel: {
    title: t('Channel-Lebenszyklus'),
    // The exact enum values shown in the Channel box's own state field (ChannelState.kt). ANONYMOUS
    // vs. REGISTERING is a real fork, not the same phase - which one a fresh channel starts in
    // depends on whether it already has an account (JourneyService.startEntryJourney): none yet ->
    // REGISTERING, already known (e.g. device-recognized) -> ANONYMOUS.
    // Step-up (STEP_UP_REQUIRED -> STEP_UP_IN_PROGRESS -> AUTHENTICATED again) isn't drawn here -
    // it's a loop back onto AUTHENTICATED, not a second fork with its own ending, and this diagram
    // is deliberately a SHAPE with the ONE branch that matters, not a full state machine; the
    // stepUp diagram (shown on that SubJourney's own hint once it's running) covers it in detail.
    steps: [t('Konto schon bekannt?'), 'AUTHENTICATED', 'LOGGED_OUT / EXPIRED'],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja ({zustand})', { zustand: 'ANONYMOUS' }),
      label: t('Nein ({zustand})', { zustand: 'REGISTERING' }),
      steps: ['AUTHENTICATED'],
    },
  },
  auto: {
    title: t('Automatisch anmelden'),
    steps: [t('Gerät erkannt?'), t('Faktor bestätigen'), t('Angemeldet')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Identifikation'), t('E-Mail bestätigen'), t('2. Faktor einrichten'), t('Angemeldet')],
    },
  },
  register: {
    title: t('Neues Konto anlegen'),
    // Reihenfolge seit ADR-17 (docs/12-entscheidungen.md): die Adresse ist Konto-Infrastruktur und
    // wird VOR jedem Anmeldeverfahren bestätigt, nicht als dessen Nebenprodukt danach
    // (RegisterState.ConfirmingEmail's eigener KDoc: "FIRST mandatory step ... before any
    // enrollment is offered").
    steps: [t('Identifikation'), t('E-Mail bestätigen'), t('2. Faktor einrichten'), t('Angemeldet')],
  },
  registerEnrollFirst: {
    title: t('Neues Konto anlegen (Einrichtung zuerst, Experiment)'),
    // RegisterEnrollFirstState (admin-umschaltbar, AdminRegistrationOrderView): komplett eigene
    // Zustände (EnrollFirst*), keine mit RegisterState geteilten Typen außer RE_IDENTIFY am Ende.
    // Reihenfolge ist fest: E-Mail (mandatory), SMS (mandatory), Passwort (mandatory) - erst danach
    // optional identifizieren, nie davor (Gegenstück zum ident-first `register` oben).
    steps: [t('E-Mail bestätigen'), t('SMS einrichten'), t('Passwort einrichten'), t('Angemeldet')],
    branch: {
      atIndex: 2,
      mainLabel: t('Nein'),
      label: t('optional identifizieren'),
      steps: [t('Identifikation'), t('Angemeldet')],
    },
  },
  login: {
    title: t('Mit E-Mail-Adresse anmelden'),
    steps: [t('E-Mail + Code/Passwort'), t('Gerät merken? (optional)'), t('Angemeldet')],
  },
  stepUp: {
    title: t('Sicherheitsniveau erhöhen'),
    steps: [t('Verfahren wählen'), t('Faktor bestätigen'), t('Niveau erreicht')],
    // The one-method dead end (StepUpStrategy): an account with a single active auth method has
    // nothing left to combine with, so re-identification is the way out instead of a dead end.
    branch: {
      atIndex: 0,
      mainLabel: t('vorhanden'),
      label: t('nur 1 Verfahren'),
      steps: [t('Erneut identifizieren'), t('Niveau erreicht')],
    },
  },
  manageMethods: {
    title: t('Anmeldeverfahren verwalten'),
    steps: [t('Niveau ausreichend?'), t('Verfahren wählen'), t('Eingerichtet')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Step-up (Faktor bestätigen)'), t('Verfahren wählen'), t('Eingerichtet')],
    },
  },
  reIdentify: {
    title: t('Erneut identifizieren'),
    // The exact ReIdentifyState names (docs/orchestrator/journey/state/ReIdentifyState.kt) -
    // shared by FAST_ACCESS/LOOKUP_LOGIN/STEP_UP alike, always this same confirmation first,
    // never a silent fallback; the identification only ever CONFIRMS the already-known account.
    steps: ['OfferReIdent', 'Identifying', 'Finished'],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: ['Cancel'],
    },
  },
  confirmPeerLogin: {
    title: t('Anmeldung im Browser bestätigen'),
    // Same anti-self-escalation gate as manageMethods (ConfirmPeerLoginStrategy.gate()): loa2
    // first. The "Nein" branch covers BOTH real starting points alike, because the strategy itself
    // does: a cold entry (no channel yet, ConfirmPeerLoginState.Requested as initialState()) and an
    // already-authenticated-but-only-loa1 channel both fail the same isSatisfied(loa2) check and
    // run through the exact same STEP_UP sub-journey - there is no separate "log in first" step in
    // the code, so this diagram doesn't invent one either. Never identification/registration even
    // from cold, see AuthIntent.CONFIRM_PEER_LOGIN's own KDoc.
    steps: [t('Bereits bei loa2?'), t('Web-Login bestätigen'), t('Bestätigt')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Anmelden bzw. Step-up (Faktor bestätigen)'), t('Web-Login bestätigen'), t('Bestätigt')],
    },
  },
  deleteAccount: {
    title: t('Konto löschen'),
    // The yes/no confirmation always comes first, unconditionally - the loa2 gate only applies
    // once accepted, never before (DeleteAccountStrategy). If it needs a step-up, that step-up
    // itself already IS the fresh proof "Faktor erneut bestätigen" would otherwise ask for again.
    steps: [t('Löschen bestätigen'), t('Niveau ausreichend?'), t('Faktor erneut bestätigen'), t('Gelöscht')],
    branch: {
      atIndex: 1,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Step-up (Faktor bestätigen)'), t('Gelöscht')],
    },
  },
  // Kein Journey-Schritt des Orchestrators (siehe docs/04-orchestrierung.md) - der Browser
  // spricht hier nie mit ihm, nur mit Keycloak selbst (webOidc.ts). Trotzdem als Diagramm gezeigt,
  // damit der Web-Kanal-Einstieg dieselbe Hover-Vorschau wie der App-Kanal bekommt.
  webLoginLoa1: {
    title: t('Login (loa1)'),
    steps: [t('Redirect zu Keycloak'), t('Login (Passwort oder Code)'), t('Zurück mit AccessToken (loa1)')],
  },
  webLoginLoa2: {
    title: t('Login (loa2)'),
    steps: [t('Redirect zu Keycloak'), t('Login + 2. Faktor'), t('Zurück mit AccessToken (loa2)')],
  },
  // Nutzt einen eigenen Test-Client (keycloak-migrations V10/V11), dessen LoA-1 orchestrator-driven
  // ist statt natives Passwort - einziger Weg, auth-qr-lookup (Kalt-Einstieg-QR-Login) am Browser
  // schon auf LoA-1 zu erreichen, ohne vorher ein Passwort einzugeben.
  webLoginQrTest: {
    title: t('Login (loa1, QR-Test-Client)'),
    steps: [t('Redirect zu Keycloak (Test-Client)'), t('Verfahren wählen (inkl. QR)'), t('Zurück mit AccessToken (loa1)')],
  },
}

/**
 * Which diagram box a REAL running journey's `stateType` (JourneyDebugStep.stateType) currently
 * corresponds to - hand-matched against the SHAPE above, not the real state machine, so a few
 * distinct real states legitimately point at the same box (e.g. AddRequested/RemoveRequested in
 * manageMethods both mean "at the loa2 decision"). Intents/states with no entry here (Finished,
 * or anything not reachable at all) simply get no highlight - the diagram still renders, just
 * without pointing at a box.
 */
export const CURRENT_STEP_BY_STATE_TYPE: Partial<Record<keyof typeof JOURNEY_DIAGRAMS, Record<string, JourneyDiagramCurrentStep>>> = {
  auto: {
    Start: { index: 0 },
    PreferredAuth: { index: 1 },
    AuthChoice: { index: 1 },
    Identifying: { branch: true, index: 0 },
    ConfirmingEmail: { branch: true, index: 1 },
    Enrolling: { branch: true, index: 2 },
    PasswordObligation: { branch: true, index: 2 },
  },
  register: {
    Identifying: { index: 0 },
    ConfirmingEmail: { index: 1 },
    Enrolling: { index: 2 },
    PasswordObligation: { index: 2 },
  },
  registerEnrollFirst: {
    EnrollFirstStart: { index: 0 },
    EnrollFirstAttestingEmail: { index: 0 },
    EnrollFirstEnrollingSms: { index: 1 },
    EnrollFirstEnrolling: { index: 1 },
    EnrollFirstConfirmingEmail: { index: 0 },
    EnrollFirstPasswordObligation: { index: 2 },
  },
  login: {
    Start: { index: 0 },
    Credential: { index: 0 },
    AdditionalFactor: { index: 0 },
    OfferBinding: { index: 1 },
  },
  stepUp: {
    Start: { index: 0 },
    AuthChoice: { index: 1 },
  },
  manageMethods: {
    AddRequested: { index: 0 },
    RemoveRequested: { index: 0 },
    Enrolling: { index: 1 },
  },
  confirmPeerLogin: {
    Requested: { index: 0 },
    Confirming: { index: 1 },
  },
  deleteAccount: {
    ConfirmPending: { index: 0 },
    ConfirmationRequired: { index: 2 },
  },
  reIdentify: {
    OfferReIdent: { index: 0 },
    Identifying: { index: 1 },
  },
}

/** JourneyDebugStep.intent (AuthIntent name) -> JOURNEY_DIAGRAMS key - one per intent, so the running intent alone names what runs. */
export const INTENT_DIAGRAM_KEY: Record<string, keyof typeof JOURNEY_DIAGRAMS> = {
  FAST_ACCESS: 'auto',
  REGISTER: 'register',
  LOOKUP_LOGIN: 'login',
  STEP_UP: 'stepUp',
  MANAGE_AUTH_METHODS: 'manageMethods',
  DELETE_ACCOUNT: 'deleteAccount',
  RE_IDENTIFY: 'reIdentify',
  CONFIRM_PEER_LOGIN: 'confirmPeerLogin',
}

/**
 * REGISTER has two runtime-toggled strategies sharing one AuthIntent (AdminRegistrationOrderView,
 * RegisterState vs. RegisterEnrollFirstState) - `intent` alone can't tell them apart, only the
 * actually reported `stateType` can (RegisterEnrollFirstState's own states are all prefixed
 * `EnrollFirst*`, see its class doc on why). Every other intent maps 1:1 via INTENT_DIAGRAM_KEY.
 */
export function diagramKeyForState(intent: string, stateType: string): keyof typeof JOURNEY_DIAGRAMS | undefined {
  if (intent === 'REGISTER' && stateType.startsWith('EnrollFirst')) return 'registerEnrollFirst'
  return INTENT_DIAGRAM_KEY[intent]
}

/**
 * Which JOURNEY_DIAGRAMS entry describes what's running right now: the innermost (actually active)
 * journey's own intent, as the backend reports it. Never the entry the user once clicked - a
 * channel runs several journeys one after another (log in, then confirm a browser login), and a
 * remembered click kept naming the first one long after it had finished. Used for both
 * JourneyStructureView's per-level hints and the "what am I doing right now" line, so both agree.
 */
export function currentJourneyDiagramKey(journeys: JourneyDebugStep[] | undefined): keyof typeof JOURNEY_DIAGRAMS | undefined {
  const innermost = journeys?.at(-1)
  return innermost ? diagramKeyForState(innermost.intent, innermost.stateType) : undefined
}

/**
 * User-facing text for App.tsx's context banner - JOURNEY_DIAGRAMS.title is written for the
 * diagram popover (a dev-facing caption, e.g. reIdentify's names the internal ReIdentifyState
 * class) and isn't fit to surface as-is; only reIdentify actually needs a different phrasing here.
 */
export function journeyContextLabel(key: keyof typeof JOURNEY_DIAGRAMS): string {
  if (key === 'reIdentify') return t('Identität erneut bestätigen')
  return JOURNEY_DIAGRAMS[key].title
}
