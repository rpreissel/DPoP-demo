import { useEffect, useMemo, useState } from 'react'
import type { DpopKeyPair } from '../dpop'
import { describeError, getJourneyLog } from '../api'
import type { JourneyLogEntryView } from '../types'

interface Props {
  dpop: DpopKeyPair | null
}

/** German labels for the raw detail keys JourneyService logs (see JourneyLogEntry/JourneyService.eventDetail/decisionDetail/outcomeDetail). */
const KEY_LABELS: Record<string, string> = {
  decision: 'Entscheidung',
  toState: 'Zielstatus',
  reason: 'Grund',
  outcome: 'Ergebnis',
  achievedAcr: 'Erreichtes ACR',
  targetAcr: 'Ziel-ACR',
  amr: 'AMR',
  factorTypes: 'Faktortypen',
  method: 'Methode',
  attemptBudgetLeft: 'Verbleibende Versuche',
  attemptedAccountId: 'Versuchtes Konto',
  attemptedPersonId: 'Versuchte Person',
  personId: 'Person',
  accountId: 'Konto',
  enrollmentRef: 'Enrollment',
  subIntent: 'Sub-Intent',
  effect: 'Effekt',
  answer: 'Antwort',
  methodInstanceId: 'Methoden-ID',
  label: 'Bezeichnung',
}

function labelFor(key: string): string {
  const last = key.split('.').pop() ?? key
  return KEY_LABELS[last] ?? last
}

interface DetailChip {
  key: string
  label: string
  value: string
}

/** Renders `next` (a full step address) and `candidateTools` (a toolId->method map) as one readable chip each, instead of exploding them into several raw key.path chips. */
function specialCasedChip(key: string, value: unknown): DetailChip | null {
  if (key === 'next' && value && typeof value === 'object') {
    const next = value as Record<string, unknown>
    const address = next.type === 'tool' ? `${next.toolId} · ${next.step}` : `${next.context} · ${next.step}`
    return { key, label: 'Nächster Schritt', value: address }
  }
  if (key === 'candidateTools' && value && typeof value === 'object') {
    const list = Object.entries(value as Record<string, string>)
      .map(([toolId, method]) => `${toolId} (${method})`)
      .join(', ')
    return list ? { key, label: 'Angebotene Tools', value: list } : null
  }
  return null
}

/** Flattens the (possibly nested) detail map into labeled chips - special-cased composites first, everything else generically by key path. */
function formatDetail(detail: Record<string, unknown>): DetailChip[] {
  const chips: DetailChip[] = []
  for (const [key, value] of Object.entries(detail)) {
    const special = specialCasedChip(key, value)
    if (special) {
      chips.push(special)
      continue
    }
    chips.push(...flatten(key, value))
  }
  return chips
}

function flatten(keyPath: string, value: unknown): DetailChip[] {
  if (value === null || value === undefined || value === '') return []
  if (Array.isArray(value)) {
    return value.length === 0 ? [] : [{ key: keyPath, label: labelFor(keyPath), value: value.join(', ') }]
  }
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) => flatten(`${keyPath}.${k}`, v))
  }
  return [{ key: keyPath, label: labelFor(keyPath), value: String(value) }]
}

const dateTimeFormat = new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium', timeStyle: 'medium' })
const timeFormat = new Intl.DateTimeFormat('de-DE', { timeStyle: 'medium' })

/** Groups entries by channelSessionId, then by journeyId. Assumes [entries] is already sorted the way rows should appear. */
function groupEntries(entries: JourneyLogEntryView[]): Map<string, Map<string, JourneyLogEntryView[]>> {
  const byChannel = new Map<string, Map<string, JourneyLogEntryView[]>>()
  for (const entry of entries) {
    const byJourney = byChannel.get(entry.channelSessionId) ?? new Map<string, JourneyLogEntryView[]>()
    byChannel.set(entry.channelSessionId, byJourney)
    const list = byJourney.get(entry.journeyId) ?? []
    byJourney.set(entry.journeyId, [...list, entry])
  }
  return byChannel
}

interface JourneyNode {
  journeyId: string
  entries: JourneyLogEntryView[]
  children: JourneyNode[]
}

/**
 * Turns one ChannelSession's flat journey map into a tree via parentJourneyId
 * (docs/04-orchestrierung.md #6: a sub-journey runs as another journey's precondition, e.g. a
 * step-up demanding a fresh RE_IDENTIFY) - a sub-journey is otherwise indistinguishable from an
 * unrelated one, even though it only exists because its parent asked for it. Every level is
 * sorted newest-activity-first.
 */
function buildJourneyTree(byJourney: Map<string, JourneyLogEntryView[]>): JourneyNode[] {
  const nodes = new Map<string, JourneyNode>()
  for (const [journeyId, journeyEntries] of byJourney) nodes.set(journeyId, { journeyId, entries: journeyEntries, children: [] })

  const roots: JourneyNode[] = []
  for (const node of nodes.values()) {
    const parentId = node.entries[0].parentJourneyId
    const parent = parentId ? nodes.get(parentId) : undefined
    ;(parent?.children ?? roots).push(node)
  }

  const latestOf = (node: JourneyNode) => node.entries.at(-1)!.createdAt
  const sortNewestFirst = (list: JourneyNode[]) => list.sort((a, b) => latestOf(b).localeCompare(latestOf(a)))
  sortNewestFirst(roots)
  for (const node of nodes.values()) sortNewestFirst(node.children)
  return roots
}

/**
 * The JourneyLog tab (docs/04-orchestrierung.md) - a demo/debug view of every journey step ever
 * recorded for THIS device's own bindingKeyRef (the backend resolves it from the DPoP proof, no
 * channelSessionId needed), filterable/groupable by channelSessionId and journeyId. Channels and
 * journeys are opaque UUIDs with no meaning of their own, so both the filters and the group
 * headings identify them by when they started (plus the intent, for a journey) instead.
 */
export function JourneyLogView({ dpop }: Props) {
  const [entries, setEntries] = useState<JourneyLogEntryView[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [channelFilter, setChannelFilter] = useState<string>('')
  const [journeyFilter, setJourneyFilter] = useState<string>('')

  function load() {
    if (!dpop) return
    setLoading(true)
    setError(null)
    getJourneyLog(dpop)
      .then((response) => setEntries(response.entries))
      .catch((err) => setError(describeError('Journey-Log laden fehlgeschlagen', err)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [dpop])

  // Oldest first within a journey's own steps (the backend returns newest-first, the natural
  // order for an API default) - groupedNewestFirst below re-sorts the ChannelSession/Journey
  // groups themselves back to newest-first, without touching this row order.
  const chronological = useMemo(() => [...entries].sort((a, b) => a.createdAt.localeCompare(b.createdAt)), [entries])

  // Filter options double as the "identity" shown for a channel/journey: its start time (plus
  // intent for a journey), newest first - the id itself is never shown, just used as the <option> value.
  const channelOptions = useMemo(() => {
    const firstSeen = new Map<string, string>()
    for (const e of chronological) if (!firstSeen.has(e.channelSessionId)) firstSeen.set(e.channelSessionId, e.createdAt)
    return [...firstSeen.entries()].sort(([, a], [, b]) => b.localeCompare(a))
  }, [chronological])

  const journeyOptions = useMemo(() => {
    const firstSeen = new Map<string, { createdAt: string; intent: string }>()
    for (const e of chronological) {
      if (channelFilter && e.channelSessionId !== channelFilter) continue
      if (!firstSeen.has(e.journeyId)) firstSeen.set(e.journeyId, { createdAt: e.createdAt, intent: e.intent })
    }
    return [...firstSeen.entries()].sort(([, a], [, b]) => b.createdAt.localeCompare(a.createdAt))
  }, [chronological, channelFilter])

  const filtered = chronological.filter(
    (e) => (!channelFilter || e.channelSessionId === channelFilter) && (!journeyFilter || e.journeyId === journeyFilter)
  )
  // Groups (ChannelSession, and Journey within it) are ordered newest-activity-first - the rows
  // inside a Journey table stay oldest-first (chronological), so "newest on top" only applies at
  // the grouping level, not to the individual steps of one journey. Sub-journeys are nested under
  // their parent (buildJourneyTree) rather than sorted in alongside it as if unrelated.
  const groupedNewestFirst = [...groupEntries(filtered).entries()]
    .map(([channelSessionId, byJourney]) => {
      const allEntries = [...byJourney.values()].flat()
      const latest = allEntries.reduce((max, e) => (e.createdAt > max ? e.createdAt : max), allEntries[0].createdAt)
      const earliest = allEntries.reduce((min, e) => (e.createdAt < min ? e.createdAt : min), allEntries[0].createdAt)
      return { channelSessionId, journeyTree: buildJourneyTree(byJourney), latest, earliest }
    })
    .sort((a, b) => b.latest.localeCompare(a.latest))

  return (
    <div className="card journey-log-card">
      <h2>Journey-Log</h2>
      <p>
        Jeder Journey-Schritt, der für dieses Gerät (dessen DPoP-Schlüssel) je aufgezeichnet wurde - über alle
        ChannelSessions hinweg, gruppiert nach ChannelSession und Journey, neueste zuerst. Nur zu Demo-/Debug-Zwecken,
        kein Audit-Trail.
      </p>

      <div className="controls">
        <label className="field-row">
          ChannelSession:
          <select
            value={channelFilter}
            onChange={(e) => {
              setChannelFilter(e.target.value)
              setJourneyFilter('')
            }}
          >
            <option value="">Alle ({channelOptions.length})</option>
            {channelOptions.map(([id, createdAt]) => (
              <option key={id} value={id}>
                {dateTimeFormat.format(new Date(createdAt))}
              </option>
            ))}
          </select>
        </label>
        <label className="field-row">
          Journey:
          <select value={journeyFilter} onChange={(e) => setJourneyFilter(e.target.value)}>
            <option value="">Alle ({journeyOptions.length})</option>
            {journeyOptions.map(([id, { createdAt, intent }]) => (
              <option key={id} value={id}>
                {intent} · {dateTimeFormat.format(new Date(createdAt))}
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={load} disabled={loading}>
          Aktualisieren
        </button>
      </div>

      {error && (
        <div className="card error-card">
          <p>{error}</p>
        </div>
      )}
      {loading && <p>Lädt…</p>}
      {!loading && filtered.length === 0 && !error && <p>Keine Einträge.</p>}

      {groupedNewestFirst.map(({ channelSessionId, journeyTree, earliest }) => (
        <div key={channelSessionId} className="journey-log-channel">
          <h3>ChannelSession vom {dateTimeFormat.format(new Date(earliest))}</h3>
          {journeyTree.map((node) => renderJourneyNode(node, 0))}
        </div>
      ))}
    </div>
  )
}

/** One journey block (header + step table), then its children recursively indented right underneath - a sub-journey is shown as part of the journey that required it, not scattered in alongside it. */
function renderJourneyNode(node: JourneyNode, depth: number) {
  const { journeyId, entries: journeyEntries, children } = node
  return (
    <div
      key={journeyId}
      className={depth > 0 ? 'journey-log-journey journey-log-journey--nested' : 'journey-log-journey'}
      style={depth > 0 ? { marginLeft: `${depth * 1.5}rem` } : undefined}
    >
      <div className={depth > 0 ? 'journey-log-journey-header journey-log-journey-header--sub' : 'journey-log-journey-header'}>
        {depth > 0 && <span className="journey-log-sub-marker">↳ Sub-Journey</span>}
        <span className="value-plain">{journeyEntries[0].intent}</span>
        <span className="value">{dateTimeFormat.format(new Date(journeyEntries[0].createdAt))}</span>
      </div>
      <div className="journey-log-table-scroll">
        <table className="journey-log-table">
          <thead>
            <tr>
              <th>Zeit</th>
              <th>Event</th>
              <th>Tool</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {journeyEntries.map((entry, index) => {
              const { toolId, ...rest } = entry.detail
              const chips = formatDetail(rest)
              return (
                <tr key={index}>
                  <td className="journey-log-time">{timeFormat.format(new Date(entry.createdAt))}</td>
                  <td>
                    <span className="badge">{entry.eventType}</span>
                  </td>
                  <td className="journey-log-tool">{typeof toolId === 'string' ? toolId : '–'}</td>
                  <td>
                    {chips.length > 0 ? (
                      <ul className="journey-log-detail-chips">
                        {chips.map((chip) => (
                          <li key={chip.key}>
                            <span className="journey-log-chip-label">{chip.label}</span>
                            <span className="journey-log-chip-value">{chip.value}</span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      '–'
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {children.map((child) => renderJourneyNode(child, depth + 1))}
    </div>
  )
}
