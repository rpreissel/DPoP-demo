import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * The German wording of a login-page template (docs/adr/ADR-033) - the `keycloak` bundle both
 * themes show, looked up by the id KcText.idOf computes.
 */
const bundle = (() => {
  const file = join(
    dirname(fileURLToPath(import.meta.url)),
    '../../keycloak-extension/src/main/resources/theme/orchestrator/login/messages/messages_de.properties',
  )
  const wordings = new Map<string, string>()
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue
    const at = line.indexOf('=')
    wordings.set(line.slice(0, at), line.slice(at + 1))
  }
  return wordings
})()

export function kc(template: string, values: Record<string, string> = {}): string {
  const wording = bundle.get(createHash('sha256').update(template, 'utf8').digest('hex').slice(0, 12)) ?? template
  return wording.replace(/\{(\w+)\}/g, (placeholder, name: string) => values[name] ?? placeholder)
}

