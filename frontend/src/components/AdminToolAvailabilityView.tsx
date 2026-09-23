import { useEffect, useState } from 'react'
import {
  fetchToolAvailability,
  setToolAvailability,
  setToolOrder,
  type ChannelToolAvailability,
  type ChannelType,
  type ToolAvailabilityEntry,
  type MethodRole,
} from '../api.ts'

const CHANNEL_LABELS: Record<ChannelType, string> = { APP: 'App-Kanal', KEYCLOAK: 'Web-Kanal' }

/** One heading per role - each role is one kind of selection list the user sees. */
const ROLE_LABELS: Record<MethodRole, string> = {
  IDENTIFIED_AUTH: 'Anmelden - Konto bekannt',
  LOOKUP_AUTH: 'Anmelden - über E-Mail-Adresse',
  IDENTIFICATION: 'Identifizieren',
  CORRELATION: 'Registerperson zuordnen',
  ENROLLMENT: 'Einrichten',
  ATTESTATION: 'E-Mail bestätigen',
  PEER_APPROVAL: 'Web-Login bestätigen',
}

/**
 * The operator's say over tools, per channel type (docs/03-tool-architektur.md, availability):
 * one list per channel, in the order that channel offers its tools. ▲/▼ move a tool and save at
 * once; the switch locks it for this channel only. Both act on the very next step of any journey.
 * Behind the admin login like every operator endpoint.
 */
export function AdminToolAvailabilityView() {
  const [channels, setChannels] = useState<ChannelToolAvailability[] | null>(null)
  const [error, setError] = useState('')

  function reload() {
    fetchToolAvailability()
      .then(setChannels)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(reload, [])

  async function run(action: () => Promise<void>) {
    try {
      setError('')
      await action()
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <div className="card">
      <h2>Verfahren je Kanal</h2>
      <p>
        Reihenfolge und Sperre gelten je Kanal und wirken sofort, auch in laufenden Vorgängen: Jede Auswahl (Anmelden,
        Identifizieren, Einrichten) zeigt ihre Verfahren in dieser Reihenfolge. Zusätzlich erklärt jeder Client selbst,
        welche Verfahren er darstellen kann - beide Filter wirken zusammen.
      </p>
      {error && <p className="error-card">{error}</p>}
      {channels === null ? (
        !error && <p>Lädt…</p>
      ) : (
        <div className="tool-channel-columns">
          {channels.map((c) => (
            <ChannelToolList key={c.channel} channel={c} run={run} />
          ))}
        </div>
      )}
    </div>
  )
}

function ChannelToolList({ channel, run }: { channel: ChannelToolAvailability; run: (a: () => Promise<void>) => void }) {
  // The tool whose lock reason is being asked for inline (instead of a blocking window.prompt).
  const [locking, setLocking] = useState<string | null>(null)
  const [reason, setReason] = useState('manuell gesperrt')
  const ids = channel.tools.map((t) => t.toolId)

  // Groups in the order the server lists them (it already sorts by role) - a selection only ever
  // shows tools of one role, so that is where an order means something.
  const groups: [MethodRole, ToolAvailabilityEntry[]][] = []
  for (const tool of channel.tools) {
    const last = groups.at(-1)
    if (last && last[0] === tool.role) last[1].push(tool)
    else groups.push([tool.role, [tool]])
  }

  /** Swaps [tool] with its neighbour in the same role and saves the channel's whole order. */
  function move(group: ToolAvailabilityEntry[], index: number, by: -1 | 1) {
    const order = [...ids]
    const a = order.indexOf(group[index].toolId)
    const b = order.indexOf(group[index + by].toolId)
    ;[order[a], order[b]] = [order[b], order[a]]
    run(() => setToolOrder(channel.channel, order))
  }

  function lock(tool: ToolAvailabilityEntry) {
    run(async () => {
      await setToolAvailability(tool.toolId, channel.channel, false, reason || undefined)
      setLocking(null)
    })
  }

  return (
    <div>
      <h3>{CHANNEL_LABELS[channel.channel]}</h3>
      {groups.map(([role, group]) => (
        <div key={role} className="tool-order-group">
          <h4>{ROLE_LABELS[role]}</h4>
          <ol className="tool-order-list">
            {group.map((tool, index) => (
              <li key={tool.toolId} className={tool.enabled ? '' : 'tool-locked'}>
                <span className="tool-order-arrows">
                  <button
                    className="secondary small"
                    aria-label={`${tool.toolId} nach oben`}
                    disabled={index === 0}
                    onClick={() => move(group, index, -1)}
                  >
                    ▲
                  </button>
                  <button
                    className="secondary small"
                    aria-label={`${tool.toolId} nach unten`}
                    disabled={index === group.length - 1}
                    onClick={() => move(group, index, 1)}
                  >
                    ▼
                  </button>
                </span>
                <span className="tool-order-name">
                  <code>{tool.toolId}</code>
                  {!tool.enabled && <span className="tool-order-reason">gesperrt{tool.reason ? `: ${tool.reason}` : ''}</span>}
                </span>
                {locking === tool.toolId ? (
                  <span className="value-with-action">
                    <input aria-label={`Grund für die Sperre von ${tool.toolId}`} value={reason} onChange={(e) => setReason(e.target.value)} />
                    <button className="small" onClick={() => lock(tool)}>
                      Sperren
                    </button>
                    <button className="secondary small" onClick={() => setLocking(null)}>
                      Abbrechen
                    </button>
                  </span>
                ) : (
                  <button
                    className="secondary small"
                    onClick={() =>
                      tool.enabled ? setLocking(tool.toolId) : run(() => setToolAvailability(tool.toolId, channel.channel, true))
                    }
                  >
                    {tool.enabled ? 'Sperren…' : 'Freigeben'}
                  </button>
                )}
              </li>
            ))}
          </ol>
        </div>
      ))}
    </div>
  )
}
