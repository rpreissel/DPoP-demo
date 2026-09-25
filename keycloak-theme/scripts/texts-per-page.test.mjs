import { describe, expect, it } from 'vitest'
import { fileURLToPath } from 'node:url'
import { idOf, pageIdOf, textsPerPage } from './texts-per-page.mjs'

const src = fileURLToPath(new URL('../src', import.meta.url))

describe('texts per page', () => {
  it('names a page after its component file', () => {
    expect(pageIdOf('OrchestratorSelect.tsx')).toBe('orchestrator-select.ftl')
  })

  it('collects the templates of a page and of what it imports', () => {
    const pages = textsPerPage(src)
    // OrchestratorSelect.tsx itself
    expect(pages['orchestrator-select.ftl']).toContain(idOf('Noch kein Konto?'))
    // KcText.idOf("Weiter"), the id messages_de.properties carries
    expect(pages['orchestrator-tool.ftl']).toContain('1e14bdf34c3b')
    expect(pages['orchestrator-select.ftl']).not.toContain('1e14bdf34c3b')
  })
})
