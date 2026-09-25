import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StepExplanation } from './StepExplanation'
import { explainToolStep, knownToolIds } from '../tools/registry'

describe('StepExplanation', () => {
  it('leads with the innermost journey purpose and names the parent a sub-journey runs for', () => {
    render(
      <StepExplanation
        journeys={[
          { journeyId: 'outer', intent: 'MANAGE_AUTH_METHODS', lifecycle: 'SUSPENDED', stateType: 'RemoveRequested', purpose: { key: 'Methode entfernen' } },
          { journeyId: 'inner', intent: 'STEP_UP', lifecycle: 'STARTED', stateType: 'AuthChoice', purpose: { key: 'Nachweis fehlt' }, note: { key: 'Auswahl' } },
        ]}
        idleReason="unbenutzt"
        does="prüft"
        actor="Sie"
        technical="Tool auth-sms · auth"
      />,
    )

    expect(screen.getByText(/Nachweis fehlt/)).toBeInTheDocument()
    expect(screen.getByText('Auswahl')).toBeInTheDocument()
    expect(screen.getByText(/Methode entfernen/)).toBeInTheDocument()
    expect(screen.queryByText('unbenutzt')).not.toBeInTheDocument()
    expect(screen.getByText('Tool auth-sms · auth')).toBeInTheDocument()
  })

  it('falls back to the idle reason when no journey runs', () => {
    render(<StepExplanation journeys={[]} idleReason="Kein Vorgang" does="x" actor="y" />)
    expect(screen.getByText('Kein Vorgang')).toBeInTheDocument()
  })

  it('has a what and a who for every tool this app knows', () => {
    for (const toolId of knownToolIds) {
      const explained = explainToolStep(toolId, 'any-step')
      expect(explained?.does, toolId).toBeTruthy()
      expect(explained?.actor, toolId).toBeTruthy()
    }
  })
})
