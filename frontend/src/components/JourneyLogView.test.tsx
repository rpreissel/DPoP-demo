import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { JourneyLogView } from './JourneyLogView'

const entry = {
  channelSessionId: 'chan-1',
  channelType: 'KEYCLOAK',
  accountId: 4,
  journeyId: 'journey-1',
  intent: 'LOGIN',
  eventType: 'Started',
  detail: {},
  createdAt: '2026-09-11T08:30:00.000Z',
}

describe('JourneyLogView', () => {
  it('shows whether a channel log entry came from App or Kc/Web', async () => {
    const fetchLog = vi.fn().mockResolvedValue({ entries: [entry], accounts: [{ accountId: 4, displayName: 'Tina Tester' }] })

    render(<JourneyLogView fetchLog={fetchLog} />)

    expect(await screen.findByText('Kc/Web')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Kc\/Web ·/ })).toBeInTheDocument()
  })

  it('names the person of a channel and offers every account in the filter, even without entries', async () => {
    const fetchLog = vi.fn().mockResolvedValue({
      entries: [entry],
      accounts: [
        { accountId: 1, displayName: 'Max Muster' },
        { accountId: 4, displayName: 'Tina Tester' },
      ],
    })

    render(<JourneyLogView fetchLog={fetchLog} />)

    expect(await screen.findByRole('heading', { name: /Tina Tester · ChannelSession/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Max Muster' })).toBeInTheDocument()
    // Every entry here has an account - so no "ohne Konto" option to pick.
    expect(screen.queryByRole('option', { name: 'ohne Konto' })).not.toBeInTheDocument()
  })
})
