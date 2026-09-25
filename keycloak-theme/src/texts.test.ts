import { describe, expect, it } from 'vitest'
import { setLanguage, t, textId } from './texts'

describe('texts', () => {
  it('computes the same id as the extension (KcText.idOf)', () => {
    // messages_de.properties: "# Quelle: Weiter" / 1e14bdf34c3b=Weiter
    expect(textId('Weiter')).toBe('1e14bdf34c3b')
  })

  it('shows the wording of the login language, German as fallback', () => {
    setLanguage('en')
    expect(t('Weiter')).toBe('Continue')
    setLanguage('fr')
    expect(t('Weiter')).toBe('Weiter')
  })

  it('fills placeholders and shows an unknown template as itself', () => {
    setLanguage('de')
    expect(t('Demo-Code: {wert}', { wert: '123456' })).toBe('Demo-Code: 123456')
    expect(t('Gibt es nicht')).toBe('Gibt es nicht')
  })
})
