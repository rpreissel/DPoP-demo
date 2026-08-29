import type { JourneyDiagramSpec } from './components/JourneyDiagram'

/**
 * The representative shape of each entry journey, shared between the start-screen hover previews
 * and the in-progress hint (JourneyStructureView) so both describe the same journey the same way.
 * Deliberately a SHAPE, not a spec - the backend decides the real order case by case
 * (docs/04-orchestrierung.md); e.g. registration can chain more than one factor before email
 * confirmation. Only the one branch each argument actually hinges on is drawn.
 */
export const JOURNEY_DIAGRAMS: Record<
  'channel' | 'auto' | 'register' | 'login' | 'stepUp' | 'manageMethods' | 'deleteAccount',
  JourneyDiagramSpec
> = {
  channel: {
    title: 'Channel-Lebenszyklus (ChannelState)',
    // The exact enum values shown in the Channel box's own state field (ChannelState.kt). ANONYMOUS
    // vs. REGISTERING is a real fork, not the same phase - which one a fresh channel starts in
    // depends on whether it already has an account (JourneyService.startEntryJourney): none yet ->
    // REGISTERING, already known (e.g. device-recognized) -> ANONYMOUS.
    // Step-up (STEP_UP_REQUIRED -> STEP_UP_IN_PROGRESS -> AUTHENTICATED again) isn't drawn here -
    // it's a loop back onto AUTHENTICATED, not a second fork with its own ending, and this diagram
    // is deliberately a SHAPE with the ONE branch that matters, not a full state machine; the
    // stepUp diagram (shown on that SubJourney's own hint once it's running) covers it in detail.
    steps: ['Konto schon bekannt?', 'AUTHENTICATED', 'LOGGED_OUT / EXPIRED'],
    branch: {
      atIndex: 0,
      mainLabel: 'Ja (ANONYMOUS)',
      label: 'Nein (REGISTERING)',
      steps: ['AUTHENTICATED'],
    },
  },
  auto: {
    title: 'Verbinden (automatisch)',
    steps: ['Gerät erkannt?', 'Faktor bestätigen', 'Angemeldet'],
    branch: {
      atIndex: 0,
      mainLabel: 'Ja',
      label: 'Nein',
      steps: ['Identifikation', '2. Faktor einrichten', 'E-Mail bestätigen', 'Angemeldet'],
    },
  },
  register: {
    title: 'Neues Konto registrieren',
    steps: ['Identifikation', '2. Faktor einrichten', 'E-Mail bestätigen', 'Angemeldet'],
  },
  login: {
    title: 'Neu anmelden',
    steps: ['E-Mail + Code/Passwort', 'Gerät merken? (optional)', 'Angemeldet'],
  },
  stepUp: {
    title: 'Sicherheitsniveau erhöhen (Step-up)',
    steps: ['Verfahren wählen', 'Faktor bestätigen', 'Niveau erreicht'],
    // The one-method dead end (StepUpStrategy): an account with a single active auth method has
    // nothing left to combine with, so re-identification is the way out instead of a dead end.
    branch: {
      atIndex: 0,
      mainLabel: 'vorhanden',
      label: 'nur 1 Verfahren',
      steps: ['Erneut identifizieren', 'Niveau erreicht'],
    },
  },
  manageMethods: {
    title: 'Verfahren verwalten',
    steps: ['Niveau ausreichend?', 'Verfahren wählen', 'Eingerichtet'],
    branch: {
      atIndex: 0,
      mainLabel: 'Ja',
      label: 'Nein',
      steps: ['Step-up (Faktor bestätigen)', 'Verfahren wählen', 'Eingerichtet'],
    },
  },
  deleteAccount: {
    title: 'Konto löschen',
    // The yes/no confirmation always comes first, unconditionally - the loa2 gate only applies
    // once accepted, never before (DeleteAccountStrategy). If it needs a step-up, that step-up
    // itself already IS the fresh proof "Faktor erneut bestätigen" would otherwise ask for again.
    steps: ['Löschen bestätigen', 'Niveau ausreichend?', 'Faktor erneut bestätigen', 'Gelöscht'],
    branch: {
      atIndex: 1,
      mainLabel: 'Ja',
      label: 'Nein',
      steps: ['Step-up (Faktor bestätigen)', 'Gelöscht'],
    },
  },
}
