import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminApp } from './AdminApp'

/** Just enough of fetch for the admin page: 401 unless the Basic header carries admin/admin. */
function fakeBackend() {
  return vi.fn(async (path: string, init?: RequestInit) => {
    const auth = (init?.headers as Record<string, string> | undefined)?.Authorization
    if (auth !== `Basic ${btoa('admin:admin')}`) return new Response('', { status: 401 })
    const body = path.endsWith('/tools/availability') ? [] : { enrollFirst: false }
    return new Response(JSON.stringify(body), { status: 200 })
  })
}

describe('AdminApp', () => {
  afterEach(() => {
    sessionStorage.clear()
    vi.unstubAllGlobals()
  })

  it('keeps the login form on wrong credentials and opens the admin tabs on the right ones', async () => {
    vi.stubGlobal('fetch', fakeBackend())
    render(<AdminApp />)

    fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'falsch' } })
    fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }))
    expect(await screen.findByText(/Anmeldung fehlgeschlagen/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: 'admin' } })
    fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }))
    expect(await screen.findByRole('tab', { name: 'Journey-Log' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abmelden' })).toBeInTheDocument()
  })
})
