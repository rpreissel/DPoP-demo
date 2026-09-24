import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IdentFscForm } from './IdentFscForm'

const max = { kvnr: 'A123456789', name: 'Muster', vorname: 'Max', geburtsdatum: '1985-06-15', fscCode: 'VALIDCODE' }

describe('IdentFscForm', () => {
  afterEach(cleanup)

  it('asks for the personal data first and PATCHes only that', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['kvnr', 'name', 'vorname', 'geburtsdatum']} demoPersons={[max]} />)

    expect(screen.queryByLabelText('Freischaltcode')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Weiter zur Freischaltcode-Eingabe' }))

    expect(onSubmit).toHaveBeenCalledWith({ kvnr: 'A123456789', name: 'Muster', vorname: 'Max', geburtsdatum: '1985-06-15' })
  })

  it('shows only the code once the backend asks for nothing but fsc', () => {
    const onSubmit = vi.fn()
    render(<IdentFscForm onSubmit={onSubmit} missingFields={['fsc']} demoPersons={[max]} />)

    expect(screen.queryByLabelText('Versichertennummer')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Identifizieren' }))

    expect(onSubmit).toHaveBeenCalledWith({ fsc: 'VALIDCODE' })
  })

  it('stays on the personal-data page when that data was rejected', () => {
    const { rerender } = render(<IdentFscForm onSubmit={vi.fn()} missingFields={['kvnr', 'name', 'vorname', 'geburtsdatum']} demoPersons={[max]} />)
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

    expect(onSubmit).toHaveBeenCalledWith({ kvnr: 'A123456789', name: 'Muster', vorname: 'Max', geburtsdatum: '1985-06-16' })
  })

  it('keeps the demo-person picker on the person whose data the form holds when going back', () => {
    const erika = { kvnr: 'B987654321', name: 'Beispiel', vorname: 'Erika', geburtsdatum: '1990-11-02', fscCode: 'ERIKA123' }
    render(<IdentFscForm onSubmit={vi.fn()} missingFields={['kvnr', 'name', 'vorname', 'geburtsdatum']} demoPersons={[max, erika]} />)
    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'B987654321' } })

    expect((screen.getByLabelText(/Testperson übernehmen/) as HTMLSelectElement).value).toBe('B987654321')
    expect((screen.getByLabelText('Vorname') as HTMLInputElement).value).toBe('Erika')
  })
})
