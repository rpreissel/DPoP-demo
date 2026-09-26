import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IdentFscForm } from './IdentFscForm'

const max = { personId: 'P000000001', kvnr: 'A123456789', familyName: 'Muster', givenNames: 'Max', birthDate: '1985-06-15', fscCode: 'VALIDCODE' }

describe('IdentFscForm', () => {
  afterEach(cleanup)

  it('asks for the personal data first and PATCHes only that', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['kvnr', 'familyName', 'givenNames', 'birthDate']} demoPersons={[max]} />)

    expect(screen.queryByLabelText('Freischaltcode')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    expect(onSubmit).toHaveBeenCalledWith({ kvnr: 'A123456789', familyName: 'Muster', givenNames: 'Max', birthDate: '1985-06-15' })
  })

  it('shows only the code once the backend asks for nothing but fsc', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['fsc']} demoPersons={[max]} />)

    expect(screen.queryByLabelText('Versichertennummer')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Identifizieren' }))

    expect(onSubmit).toHaveBeenCalledWith({ fsc: 'VALIDCODE' })
  })

  it('stays on the personal-data page when that data was rejected', () => {
    const { rerender } = render(<IdentFscForm onSubmit={vi.fn()} missingFields={['kvnr', 'familyName', 'givenNames', 'birthDate']} demoPersons={[max]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    rerender(<IdentFscForm onSubmit={vi.fn()} error="Die Angaben passen zu keiner versicherten Person" demoPersons={[max]} />)

    expect(screen.getByLabelText('Versichertennummer')).toBeTruthy()
    expect(screen.getByText('Die Angaben passen zu keiner versicherten Person')).toBeTruthy()
  })

  it('stays on the code page when the code was rejected', () => {
    const { rerender } = render(<IdentFscForm onSubmit={vi.fn()} missingFields={['fsc']} demoPersons={[max]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Identifizieren' }))

    rerender(<IdentFscForm onSubmit={vi.fn()} error="Freischaltcode ungueltig oder abgelaufen" demoPersons={[max]} />)

    expect(screen.getByLabelText('Freischaltcode')).toBeTruthy()
  })

  it('sends corrected personal data on its own, to be checked again', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['fsc']} demoPersons={[max]} />)

    fireEvent.click(screen.getByRole('button', { name: 'Angaben ändern' }))
    fireEvent.change(screen.getByLabelText('Geburtsdatum'), { target: { value: '1985-06-16' } })
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    expect(onSubmit).toHaveBeenCalledWith({ kvnr: 'A123456789', familyName: 'Muster', givenNames: 'Max', birthDate: '1985-06-16' })
  })

  it('keeps the demo-person picker on the person whose data the form holds when going back', () => {
    const erika = { personId: 'P000000002', kvnr: 'B987654321', familyName: 'Beispiel', givenNames: 'Erika', birthDate: '1990-11-02', fscCode: 'ERIKA123' }
    render(<IdentFscForm onSubmit={vi.fn()} missingFields={['kvnr', 'familyName', 'givenNames', 'birthDate']} demoPersons={[max, erika]} />)
    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'P000000002' } })

    expect((screen.getByLabelText(/Testperson übernehmen/) as HTMLSelectElement).value).toBe('P000000002')
    expect((screen.getByLabelText('Vorname') as HTMLInputElement).value).toBe('Erika')
  })

  it('asks a Partner for the Partnernummer instead of the KVNR, and sends only that (ADR-34)', () => {
    const paula = { personId: 'P000000004', kvnr: null, familyName: 'Schulz', givenNames: 'Paula', birthDate: '1982-08-08', fscCode: 'PAULA2026' }
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['kvnr', 'familyName', 'givenNames', 'birthDate']} demoPersons={[max, paula]} />)
    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'P000000004' } })

    expect(screen.queryByLabelText('Versichertennummer')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    expect(onSubmit).toHaveBeenCalledWith({ partnernr: 'P000000004', familyName: 'Schulz', givenNames: 'Paula', birthDate: '1982-08-08' })
  })

  it('switches to the Partnernummer only on request - the KVNR comes first', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['kvnr', 'familyName', 'givenNames', 'birthDate']} demoPersons={[max]} />)

    fireEvent.click(screen.getByRole('button', { name: 'Ich habe keine Versichertennummer' }))
    fireEvent.change(screen.getByLabelText('Partnernummer'), { target: { value: 'P000000001' } })
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    expect(onSubmit).toHaveBeenCalledWith({ partnernr: 'P000000001', familyName: 'Muster', givenNames: 'Max', birthDate: '1985-06-15' })
  })
})
