import { useEffect, useState } from 'react'
import { fetchServerInfo, type ChannelType, type ServerInfo } from '../api.ts'
import { knownToolIds } from '../tools/registry'

interface UnavailableToolsProps {
  /** Whose operator locks count - a lock for the Web channel does not affect the App, and vice versa. */
  channel: ChannelType
  /**
   * The client's own declared availability, where this page knows it (the App channel's
   * "Erweitert"). The Web channel's declaration lives in the Keycloak extension, so there only the
   * operator locks are shown.
   */
  availableTools?: string[]
}

/**
 * Both availability axes only ever show up as an ABSENCE in a candidate list (docs/05-api.md) -
 * a tool that's off simply never appears in `stepData.options`, with no indication why. This
 * says so, deliberately small and collapsed: a one-line hint that opens to the list with reasons.
 * The operator locks come from the public server status, not the admin endpoint: a channel user
 * has no admin login, and needs none to see that a tool is switched off.
 */
export function UnavailableTools({ channel, availableTools }: UnavailableToolsProps) {
  const [locks, setLocks] = useState<ServerInfo['disabledTools']>([])

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setLocks(info.disabledTools.filter((t) => t.channel === channel)))
      .catch(() => setLocks([]))
  }, [channel])

  const candidates = availableTools ? knownToolIds : locks.map((l) => l.toolId)
  const rows = candidates
    .map((toolId) => {
      const clientDisabled = availableTools ? !availableTools.includes(toolId) : false
      const lock = locks.find((e) => e.toolId === toolId)
      return { toolId, clientDisabled, lock }
    })
    .filter((row) => row.clientDisabled || row.lock)

  if (rows.length === 0) return null

  return (
    <details className="unavailable-tools">
      <summary>
        {rows.length} Verfahren nicht verfügbar
      </summary>
      <ul>
        {rows.map((row) => (
          <li key={row.toolId}>
            <code>{row.toolId}</code>{' '}
            {[
              row.clientDisabled && 'auf diesem Client deaktiviert',
              row.lock && `gesperrt${row.lock.reason ? ` - ${row.lock.reason}` : ''}`,
            ]
              .filter(Boolean)
              .join(' · ')}
          </li>
        ))}
      </ul>
    </details>
  )
}
