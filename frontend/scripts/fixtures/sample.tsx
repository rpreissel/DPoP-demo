// Fixture for text-catalog.test.ts - never imported by the app.
import { t } from '../../src/texts'
import { Tx } from '../../src/Tx'

export const joined = t('Ein ' + 'Satz')
export const plain = t('Weiter zu Nect')
export const withValue = t('Noch {anzahl} Versuche', { anzahl: 2 })
export const element = <Tx text="Ihr Code lautet: {code}" code={<strong>x</strong>} />
export function variable(wording: string) {
  return t(wording)
}
export const interpolated = (n: number) => t(`Noch ${n} Versuche`)
