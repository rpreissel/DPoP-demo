import { useEffect, useState } from 'react'
import { fetchToolAvailability, setToolAvailability, type ToolAvailabilityEntry } from '../api.ts'

/**
 * The backend-side kill-switch (docs/03-tool-architektur.md, availability): global, takes effect
 * on the very next step of any journey - no DPoP, no auth guard (demo scope, same as the endpoint
 * itself), not part of the auth flow's `next`-driven routing (routing.ts) since it isn't a step in
 * any journey.
 */
function groupByMethod(entries: ToolAvailabilityEntry[]): [string, ToolAvailabilityEntry[]][] {
  const groups = new Map<string, ToolAvailabilityEntry[]>()
  for (const entry of entries) {
    const group = groups.get(entry.method)
    if (group) group.push(entry)
    else groups.set(entry.method, [entry])
  }
  return [...groups.entries()]
}

export function AdminToolAvailabilityView() {
  const [entries, setEntries] = useState<ToolAvailabilityEntry[] | null>(null)
  const [error, setError] = useState('')

  function reload() {
    fetchToolAvailability()
      .then(setEntries)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(reload, [])

  async function toggle(entry: ToolAvailabilityEntry) {
    try {
      setError('')
      const reason = entry.enabled ? window.prompt(`Grund für die Sperre von ${entry.toolId}?`, 'manuell gesperrt') ?? undefined : undefined
      await setToolAvailability(entry.toolId, !entry.enabled, reason)
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <div className="card">
      <h2>Admin: Tool-Verfügbarkeit</h2>
      <p>
        Globaler Kill-Switch pro Tool - wirkt sofort auf jede laufende Journey, kein Neustart nötig. Das ist
        unabhängig davon, welche Tools ein einzelner Client bei Kanal-Erzeugung selbst als verfügbar erklärt
        (die Checkliste "Erweiterte Optionen" auf dem Start-Bildschirm) - beide Sperren wirken zusammen.
      </p>
      {error && <p className="error-card">{error}</p>}
      {entries === null ? (
        <p>Lädt…</p>
      ) : (
        // Grouped by method, not just alphabetical by toolId - auth-sms/auth-sms-lookup/enroll-sms
        // belong together, but "auth-*" and "enroll-*" would otherwise sort far apart. The server
        // already returns entries pre-sorted by (method, toolId); grouping here just adds headings.
        groupByMethod(entries).map(([method, group]) => (
          <div key={method} className="tool-availability-group">
            <h3>{method}</h3>
            <ul className="status-list">
              {group.map((entry) => (
                <li key={entry.toolId}>
                  <span className="label">{entry.toolId}</span>
                  <span className="value-with-action">
                    <span className="value">{entry.enabled ? 'aktiv' : `gesperrt${entry.reason ? ` (${entry.reason})` : ''}`}</span>
                    <button className="secondary small" onClick={() => toggle(entry)}>
                      {entry.enabled ? 'Sperren' : 'Freigeben'}
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          </div>
        ))
      )}
    </div>
  )
}
