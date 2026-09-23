import { useEffect, useState } from 'react'
import { fetchServerInfo, type ServerInfo } from '../api.ts'
import { knownToolIds } from '../tools/registry'

interface UnavailableToolsProps {
  /** The client's own declared availability ("Erweitert" in the demo tab, docs/03-tool-architektur.md). */
  availableTools: string[]
}

/**
 * Both availability axes only ever show up as an ABSENCE in a candidate list (docs/05-api.md) -
 * a tool that's off simply never appears in `stepData.options`, with no indication why. This
 * makes that visible on the Demo page itself: which known tools are currently unavailable, and
 * on which axis (this client's own declared support, or the backend-wide kill-switch, with its
 * reason) - most useful right after toggling something on the admin page or simulating an outage.
 * The backend locks come from the public server status, not the admin endpoint: a channel user
 * has no admin login, and needs none to see that a tool is switched off.
 */
export function UnavailableTools({ availableTools }: UnavailableToolsProps) {
  const [disabled, setDisabled] = useState<ServerInfo['disabledTools']>([])

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setDisabled(info.disabledTools))
      .catch(() => setDisabled([]))
  }, [])

  const rows = knownToolIds
    .map((toolId) => {
      const clientDisabled = !availableTools.includes(toolId)
      const lock = disabled.find((e) => e.toolId === toolId)
      return { toolId, clientDisabled, adminDisabled: lock !== undefined, reason: lock?.reason }
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
