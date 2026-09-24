import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * The German wording the app shows for a template (docs/adr/ADR-033). The suite names texts by their
 * source template, like the unit tests do; the browser runs in German (playwright.config.ts), so
 * what it shows is the de bundle's rewording - looked up here by the same id the app computes.
 */
const bundle = (() => {
  const file = join(dirname(fileURLToPath(import.meta.url)), '../../src/main/resources/texts/app/texts_de.properties')
  const wordings = new Map<string, string>()
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue
    const at = line.indexOf('=')
    wordings.set(line.slice(0, at), line.slice(at + 1).replace(/\\(.)/g, '$1'))
  }
  return wordings
})()

export function ui(template: string): string {
  return bundle.get(createHash('sha256').update(template, 'utf8').digest('hex').slice(0, 12)) ?? template
}

/** For matching inside a larger text or a regex. */
export function uiPattern(...templates: string[]): RegExp {
  return new RegExp(templates.map((t) => ui(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'))
}
