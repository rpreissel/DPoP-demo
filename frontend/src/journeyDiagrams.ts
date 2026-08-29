import type { JourneyDiagramSpec } from './components/JourneyDiagram'

/**
 * The representative shape of each entry journey, shared between the start-screen hover previews
 * and the in-progress hint (SessionStatusView) so both describe the same journey the same way.
 * Deliberately a SHAPE, not a spec - the backend decides the real order case by case
 * (docs/04-orchestrierung.md); e.g. registration can chain more than one factor before email
 * confirmation. Only the one branch each argument actually hinges on is drawn.
 */
export const JOURNEY_DIAGRAMS: Record<
  'auto' | 'register' | 'login' | 'stepUp' | 'manageMethods' | 'deleteAccount',
  JourneyDiagramSpec
> = {
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
