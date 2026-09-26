import { describe, expect, it } from 'vitest'
import { setTexts, t, textId } from './texts'

describe('texts', () => {
  it('computes the same id as the extension (KcText.idOf)', () => {
    // messages_de.properties: "# Quelle: Weiter" / weiter-1e14bd=Weiter
    expect(textId('Weiter')).toBe('weiter-1e14bd')
  })

  it('shows the wording Keycloak rendered into the page', () => {
    setTexts({ [textId('Weiter')]: 'Continue' })
    expect(t('Weiter')).toBe('Continue')
  })

  it('fills placeholders, and without a wording shows the template itself', () => {
    setTexts({ [textId('Demo-Code: {wert}')]: 'Demo code: {wert}' })
    expect(t('Demo-Code: {wert}', { wert: '123456' })).toBe('Demo code: 123456')
    setTexts(undefined)
    expect(t('Weiter')).toBe('Weiter')
  })
})
