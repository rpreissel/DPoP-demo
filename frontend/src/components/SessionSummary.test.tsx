import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SessionSummary } from './SessionSummary'

describe('SessionSummary', () => {
  it('says so when nothing is proven yet', () => {
    render(<SessionSummary signedIn={false} />)
    expect(screen.getByText('Nicht angemeldet')).toBeInTheDocument()
    expect(screen.queryByText('acr')).not.toBeInTheDocument()
  })

  it('shows what is proven so far while signing in', () => {
    render(<SessionSummary signedIn={false} name="Max Muster" acr="loa1" amr={['fsc']} />)
    expect(screen.getByText('Anmeldung läuft')).toBeInTheDocument()
    expect(screen.getByText('fsc')).toBeInTheDocument()
  })

  it('names the signed-in person with role, level and methods', () => {
    render(<SessionSummary signedIn name="Max Muster" role="Versicherter" acr="loa2" amr={['sms', 'password']} />)
    expect(screen.getByText('Max Muster (Versicherter)')).toBeInTheDocument()
    expect(screen.getByText('loa2')).toBeInTheDocument()
    expect(screen.getByText('sms, password')).toBeInTheDocument()
  })
})
