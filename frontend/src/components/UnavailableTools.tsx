import { useEffect, useState } from 'react'
import { fetchToolAvailability, type ToolAvailabilityEntry } from '../api.ts'
import { knownToolIds } from '../tools/registry'

interface UnavailableToolsProps {
  /** The client's own declared availability (Einstellungen, docs/03-tool-architektur.md). */
  availableTools: string[]
}

/**
 * Both availability axes only ever show up as an ABSENCE in a candidate list (docs/05-api.md) -
 * a tool that's off simply never appears in `stepData.options`, with no indication why. This
 * makes that visible on the Demo page itself: which known tools are currently unavailable, and
 * on which axis (this client's own declared support, or the backend-wide kill-switch, with its
 * reason) - most useful right after toggling something in Einstellungen or simulating an outage.
 */
export function UnavailableTools({ availableTools }: UnavailableToolsProps) {
  const [adminEntries, setAdminEntries] = useState<ToolAvailabilityEntry[] | null>(null)

  useEffect(() => {
    fetchToolAvailability()
      .then(setAdminEntries)
      .catch(() => setAdminEntries(null))
  }, [])

  const rows = knownToolIds
    .map((toolId) => {
      const clientDisabled = !availableTools.includes(toolId)
      const adminEntry = adminEntries?.find((e) => e.toolId === toolId)
      const adminDisabled = adminEntry ? !adminEntry.enabled : false
      return { toolId, clientDisabled, adminDisabled, reason: adminEntry?.reason }
    })
    .filter((row) => row.clientDisabled || row.adminDisabled)

  if (rows.length === 0) return null

  return (
    <div className="card">
      <h3 className="section-heading">Nicht verfügbare Verfahren</h3>
      <ul className="status-list">
        {rows.map((row) => (
          <li key={row.toolId}>
            <span className="label">{row.toolId}</span>
            <span className="value">
              {[
                row.clientDisabled && 'auf diesem Client deaktiviert',
                row.adminDisabled && `vom Backend gesperrt${row.reason ? ` (${row.reason})` : ''}`,
              ]
                .filter(Boolean)
                .join(' · ')}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
