import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { JourneyLogView } from './JourneyLogView'
import type { JourneyLogResponse } from '../types'

describe('JourneyLogView', () => {
  it('shows whether a channel log entry came from App or Kc/Web', async () => {
    const fetchLog: () => Promise<JourneyLogResponse> = vi.fn().mockResolvedValue({
      entries: [
        {
          channelSessionId: 'chan-1',
          channelType: 'KEYCLOAK',
          journeyId: 'journey-1',
          intent: 'LOGIN',
          eventType: 'Started',
          detail: {},
          createdAt: '2026-09-11T08:30:00.000Z',
        },
      ],
    })

    render(<JourneyLogView fetchLog={fetchLog} />)

    expect(await screen.findByText('Kc/Web')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Kc\/Web ·/ })).toBeInTheDocument()
  })
})
