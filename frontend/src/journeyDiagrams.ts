import type { JourneyDiagramSpec } from './components/JourneyDiagram'

/**
 * The representative shape of each entry journey, shared between the start-screen hover previews
 * and the in-progress hint (SessionStatusView) so both describe the same journey the same way.
 * Deliberately a SHAPE, not a spec - the backend decides the real order case by case
 * (docs/04-orchestrierung.md); e.g. registration can chain more than one factor before email
 * confirmation. Only the one branch each argument actually hinges on is drawn.
 */
export const JOURNEY_DIAGRAMS: Record<'auto' | 'register' | 'login', JourneyDiagramSpec> = {
  auto: {
    title: 'Verbinden (automatisch)',
    steps: ['Gerät erkannt?', 'Faktor bestätigen', 'Fertig'],
    branch: {
      atIndex: 0,
      mainLabel: 'Ja',
      label: 'Nein',
      steps: ['Identifikation', '2. Faktor einrichten', 'E-Mail bestätigen', 'Fertig'],
    },
  },
  register: {
    title: 'Neuen Account registrieren',
    steps: ['Identifikation', '2. Faktor einrichten', 'E-Mail bestätigen', 'Fertig'],
  },
  login: {
    title: 'Login ohne DPoP',
    steps: ['E-Mail + Code/Passwort', 'Gerät merken? (optional)', 'Fertig'],
  },
}
