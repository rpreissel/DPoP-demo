import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { bundleOf, catalogOf, scanSource } from './text-catalog.mjs'

const here = dirname(fileURLToPath(import.meta.url))

describe('text catalog (scripts/text-catalog.mjs)', () => {
  it('finds no template in the application that is not a string literal', () => {
    const { problems } = catalogOf(join(here, '..', 'src'))
    expect(problems).toEqual([])
  })

  it('collects t() and <Tx text>, and names every non-literal template by file and line', () => {
    const file = 'fixtures/sample.tsx'
    const { found, problems } = scanSource(file, readFileSync(join(here, file), 'utf8'))
    const templates = found.map((f) => f.template)
    expect(templates).toEqual(expect.arrayContaining(['Ein Satz', 'Weiter zu Nect', 'Noch {anzahl} Versuche', 'Ihr Code lautet: {code}']))
    expect(problems).toEqual([
      expect.stringMatching(/^fixtures\/sample\.tsx:10: /),
      expect.stringMatching(/^fixtures\/sample\.tsx:12: /),
    ])
  })

  it('files the foreign systems’ pages under their own bundle', () => {
    expect(bundleOf('entries/nect/NectApp.tsx')).toBe('nect')
    expect(bundleOf('entries/personenverzeichnis/PersonenverzeichnisApp.tsx')).toBe('personenverzeichnis')
    expect(bundleOf('kobilSdk.ts')).toBe('kobil')
    expect(bundleOf('tools/sms/index.tsx')).toBe('app')
  })
})
