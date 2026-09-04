import { useEffect, useRef, useState } from 'react'
import { abandonKcTool, activateKcTool, fetchKcRestoreData, resumeKcChannel, upsertKcChannel, type AmrEntry } from '../kcApi'
import { getOrFetchKcSigningKey, type KcSigningKey } from '../kcSigning'
import { describeError } from '../api'
import { knownToolIds, renderToolStep } from '../tools/registry'
import type { ToolRenderContext } from '../tools/types'
import type { AuthData, ChannelResponse, DemoInfo, Next, StepData } from '../types'
import { SelectMethodView } from './SelectMethodView'

type Anchor = { kcAuthSessionId?: string; kcSessionId?: string }

/** What App.tsx mirrors into its own top-level DebugSidebar - see MockKeycloakView's own doc comment for why this lives there instead of nested in here. */
export interface MockKeycloakState {
  channelSessionId?: string
  channelState?: string
  next?: Next
  stepData?: StepData
  demo?: DemoInfo
  authData?: AuthData
}

interface MockKeycloakViewProps {
  onStateChange: (state: MockKeycloakState) => void
}

/**
 * Demo/test stand-in for the real Keycloak Java SPI plugin (bd DPoP-demo-f9o.9, docs/ideen/
 * web-keycloak-kanal.md #7) - drives the SAME facade-neutral endpoints the App channel uses (tool
 * steps rendered via the actual tools/* components, see [ToolRenderContext.proof]), signed with a
 * Keycloak peer-auth assertion instead of DPoP. No `context.setUser()`/Protocol-Mapper equivalent:
 * `authData` is just shown, not written anywhere real.
 *
 * Renders as plain tab content, like every other tab (JourneyLogView, AuthenticationCompletedView,
 * ...) - no own app-shell/app-main wrapper, and no own DebugSidebar: nested one level inside the
 * App-channel's 960px-wide `.app` column, both had no real viewport width to work with and sat
 * cramped against the tool content. [onStateChange] reports the bits App.tsx's shared, top-level
 * DebugSidebar needs instead.
 */
export function MockKeycloakView({ onStateChange }: MockKeycloakViewProps) {
  const [signingKey, setSigningKey] = useState<KcSigningKey | null>(null)
  const [keyError, setKeyError] = useState('')
  const [channelSessionId, setChannelSessionId] = useState<string | null>(null)
  const [anchor, setAnchor] = useState<Anchor | null>(null)
  const [response, setResponse] = useState<ChannelResponse | null>(null)
  const [error, setError] = useState('')

  // Start form - covers both anchor cases (docs/ideen/web-keycloak-kanal.md #6/#8): initial login
  // (no sub) vs. step-up (sub already known) - either with a target level or without one at all
  // (Keycloak resuming/reflecting an existing session without asking for more). Every start opens
  // its own fresh channel, step-up included (docs/ideen/web-keycloak-kanal.md #6: a step-up's
  // `ChannelSession` is never found/reused, only its evidence is carried over via the amr fields
  // below - simulating what the real Authenticator would have recovered from its own
  // UserSession-note hand-off).
  const [startMode, setStartMode] = useState<'login' | 'sub'>('login')
  const [accountIdInput, setAccountIdInput] = useState('')
  const [targetAcrInput, setTargetAcrInput] = useState('')
  // Comma-separated nativeToolIds (docs/ideen/web-keycloak-kanal.md #6/#9) - method/loa/factorTypes
  // aren't entered here at all anymore, they're fixed per authenticator TYPE server-side
  // (NativeAuthenticatorRegistry). Known demo ids: kc-sms-form, kc-password-form, kc-otp-form.
  const [nativeToolIdsInput, setNativeToolIdsInput] = useState('kc-sms-form')
  // A token fetched via GET .../restore-data (docs/ideen/web-keycloak-kanal.md #6) - stands in for
  // what the real Authenticator's end-of-flow lifecycle hook would stash in a Keycloak
  // UserSession note, and kcSessionId is the value it's bound to (RestoreDataCodec checks a
  // resubmission's own assertion carries the SAME one). Deliberately never cleared by `reset()` -
  // a resumed/reset UI still represents the same browser, whose Keycloak session note would
  // still be there.
  const [restoreDataToken, setRestoreDataToken] = useState<string | null>(null)
  const [restoreKcSessionId, setRestoreKcSessionId] = useState<string | null>(null)
  const [restoreError, setRestoreError] = useState('')

  useEffect(() => {
    getOrFetchKcSigningKey()
      .then(setSigningKey)
      .catch((err) => setKeyError(describeError('Signing-Key laden fehlgeschlagen', err)))
  }, [])

  useEffect(() => {
    onStateChange({
      channelSessionId: channelSessionId ?? undefined,
      channelState: response?.channel.state,
      next: response?.next,
      stepData: response?.stepData,
      demo: response?.demo,
      authData: response?.authData,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channelSessionId, response])

  // Client-side-only convenience, not an orchestrator concern (docs/ideen/web-keycloak-kanal.md
  // #6/#8): every "Sub vorhanden" run opens its own fresh channel, never a reused one - this
  // effect just mirrors the last channel's own authData into the form fields, so a tester doesn't
  // have to retype accountId/amr by hand for the next step-up. That mirroring IS the faithful
  // simulation now: it stands in for the real Authenticator reading its own evidence back out of
  // a Keycloak UserSession-note and handing it to the newly opened channel as its initial amr
  // (docs/ideen/web-keycloak-kanal.md #6). Only fires while accountId is actually known (an
  // initial-login channel's still-empty authData must never clear a prefill that came from an
  // earlier, already-authenticated channel).
  useEffect(() => {
    const authData = response?.authData
    if (authData?.accountId == null) return
    setAccountIdInput(String(authData.accountId))
    // Deliberately no amr/nativeToolId prefill here anymore: authData.amr reports method names
    // (what was proven), not nativeToolIds (which authenticator TYPE proved it) - the mock has no
    // reverse lookup from one to the other, so a tester re-enters nativeToolIds by hand.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [response])

  const next = response?.next
  const stepData = response?.stepData
  const demo = response?.demo

  // Same single-candidate auto-collapse the App channel handles (JourneyService.nextFor): when
  // only one tool was offered, `next` already points straight at it, no toolSessionId yet - the
  // client is expected to activate it itself, no separate selection screen was ever sent.
  const activatingRef = useRef<string | null>(null)
  useEffect(() => {
    if (!signingKey || !channelSessionId || !anchor || !next || next.type !== 'tool' || !next.toolId || next.toolSessionId) return
    if (activatingRef.current === next.toolId) return
    const toolId = next.toolId
    activatingRef.current = toolId
    activateKcTool(signingKey, anchor, channelSessionId, toolId)
      .then(setResponse)
      .catch((err) => setError(describeError('Tool-Aktivierung fehlgeschlagen', err)))
      .finally(() => {
        if (activatingRef.current === toolId) activatingRef.current = null
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signingKey, channelSessionId, anchor, next])

  function reset() {
    setChannelSessionId(null)
    setAnchor(null)
    setResponse(null)
    setError('')
  }

  async function start() {
    if (!signingKey) return
    const accountId = startMode === 'sub' ? Number(accountIdInput) : undefined
    if (startMode === 'sub' && !accountId) {
      setError('Für "Sub vorhanden" wird eine bekannte accountId benötigt (siehe Demo-Tab, Sicherheitsdetails).')
      return
    }
    const amr: AmrEntry[] = nativeToolIdsInput
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((nativeToolId) => ({ nativeToolId, amrSourceId: `${nativeToolId}-exec-1` }))
    try {
      setError('')
      // Always a fresh channel, login or step-up alike (docs/ideen/web-keycloak-kanal.md #6) -
      // only the anchor shape differs. A step-up reuses restoreKcSessionId (the value a prior
      // "RestoreData holen" click fetched a token for) as its OWN kcSessionId when one is on hand
      // - RestoreDataCodec.decode only honors a token bound to the resubmitting assertion's exact
      // kcSessionId, so a freshly fabricated one here would make restoreDataToken below silently
      // useless.
      const id = crypto.randomUUID()
      const a: Anchor =
        startMode === 'login'
          ? { kcAuthSessionId: `mock-auth-session-${crypto.randomUUID()}` }
          : { kcSessionId: restoreDataToken && restoreKcSessionId ? restoreKcSessionId : `mock-user-session-${crypto.randomUUID()}` }
      const result = await upsertKcChannel(
        signingKey,
        id,
        a,
        accountId,
        startMode === 'sub' ? targetAcrInput || undefined : undefined,
        startMode === 'sub' && amr.length > 0 ? amr : undefined,
        startMode === 'sub' ? (restoreDataToken ?? undefined) : undefined,
      )
      setChannelSessionId(id)
      setAnchor(a)
      setResponse(result)
    } catch (err) {
      setError(describeError('Start fehlgeschlagen', err))
    }
  }

  /** Simulates the Authenticator's end-of-flow lifecycle hook (docs/ideen/web-keycloak-kanal.md #6) fetching a fresh RestoreData token to stash in a Keycloak UserSession note. */
  async function fetchRestoreData() {
    if (!signingKey || !channelSessionId || !anchor) return
    const kcSessionId = anchor.kcSessionId ?? `mock-user-session-${crypto.randomUUID()}`
    try {
      setRestoreError('')
      const token = await fetchKcRestoreData(signingKey, channelSessionId, anchor, kcSessionId)
      setRestoreDataToken(token ?? null)
      setRestoreKcSessionId(token ? kcSessionId : null)
    } catch (err) {
      setRestoreError(describeError('RestoreData holen fehlgeschlagen', err))
    }
  }

  async function simulateNativeAuthenticator() {
    if (!signingKey || !channelSessionId || !anchor) return
    const accountId = accountIdInput ? Number(accountIdInput) : undefined
    const amr: AmrEntry[] = nativeToolIdsInput
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((nativeToolId) => ({ nativeToolId, amrSourceId: `${nativeToolId}-exec-1` }))
    try {
      setError('')
      await upsertKcChannel(signingKey, channelSessionId, anchor, accountId, undefined, amr)
      // "Resume ohne Tool-Aufruf" (docs/ideen/web-keycloak-kanal.md #8): a SEPARATE, plain
      // follow-up call with no native params at all - proves the merged evidence, and each
      // entry's own source ("kc" vs "orchestrator"), survives independently of the call that
      // produced it, exactly as the real OrchestratorAuthenticator would see it on its next visit.
      const resumed = await resumeKcChannel(signingKey, channelSessionId, anchor)
      setResponse(resumed)
    } catch (err) {
      setError(describeError('Native Simulation fehlgeschlagen', err))
    }
  }

  async function selectMethod(toolId: string) {
    if (!signingKey || !channelSessionId || !anchor) return
    try {
      setError('')
      const result = await activateKcTool(signingKey, anchor, channelSessionId, toolId)
      setResponse(result)
    } catch (err) {
      setError(describeError('Tool-Aktivierung fehlgeschlagen', err))
    }
  }

  async function switchMethod() {
    if (!signingKey || !anchor || next?.type !== 'tool' || !next.toolSessionId || !next.toolId) return
    try {
      setError('')
      const result = await abandonKcTool(signingKey, anchor, next.toolSessionId, next.toolId)
      setResponse(result)
    } catch (err) {
      setError(describeError('Wechsel fehlgeschlagen', err))
    }
  }

  const toolCtx: ToolRenderContext | undefined =
    signingKey && anchor && next?.type === 'tool' && next.toolId
      ? {
          step: next.step,
          toolId: next.toolId,
          toolSessionId: next.toolSessionId,
          proof: { kind: 'kc', key: signingKey, anchor },
          stepData,
          demo,
          onResult: setResponse,
          onError: (message) => setError(message),
        }
      : undefined
  // Only known-to-this-frontend tools can render a form here at all - a candidate the App
  // channel's own tool modules never learned to draw (shouldn't happen given today's shared
  // catalog, but the fallback keeps the mock from silently showing nothing).
  const toolRenderable = toolCtx && knownToolIds.includes(toolCtx.toolId)
  const authenticated = next?.type === 'orchestrator' && next.context === 'authentication' && next.step === 'authenticated'

  return (
    <>
      <div className="card">
        <h2>Mock-Keycloak</h2>
        <p>
          Simuliert Keycloaks <code>OrchestratorAuthenticator</code> (docs/ideen/web-keycloak-kanal.md) - ruft dieselben
          fassadenneutralen Endpunkte wie der App-Kanal auf, mit einer signierten Peer-Auth-Assertion statt DPoP. Nur für
          Demo/Test: kein echtes <code>context.setUser()</code>, <code>authData</code> wird hier nur angezeigt.
        </p>

        {keyError && <div className="error-card">{keyError}</div>}

        {!channelSessionId && signingKey && (
          <>
            <div className="form-group">
              <label>Anker</label>
              <div className="form-actions" style={{ marginTop: 0 }}>
                <label>
                  <input type="radio" checked={startMode === 'login'} onChange={() => setStartMode('login')} /> Initialer Login (kein sub)
                </label>
                <label>
                  <input type="radio" checked={startMode === 'sub'} onChange={() => setStartMode('sub')} /> Sub vorhanden (Step-up)
                </label>
              </div>
            </div>

            {startMode === 'sub' && (
              <div className="form-grid">
                {accountIdInput && (
                  <p className="hint" style={{ gridColumn: '1 / -1', margin: 0 }}>
                    accountId/amr vorbelegt aus der zuletzt bekannten authData - simuliert, was der reale
                    Authenticator hier aus seiner eigenen Keycloak-UserSession-Note zurückgelesen hätte, um den neu
                    angelegten Kanal damit zu befüllen (docs/ideen/web-keycloak-kanal.md #6/#8).
                  </p>
                )}
                <div className="form-group">
                  <label htmlFor="kc-account-id">accountId</label>
                  <input id="kc-account-id" value={accountIdInput} onChange={(e) => setAccountIdInput(e.target.value)} placeholder="siehe Demo-Tab, Sicherheitsdetails" />
                </div>
                <div className="form-group">
                  <label htmlFor="kc-target-acr">targetAcr (leer = kein Step-up)</label>
                  <select id="kc-target-acr" value={targetAcrInput} onChange={(e) => setTargetAcrInput(e.target.value)}>
                    <option value="">kein Step-up</option>
                    <option value="loa1">loa1</option>
                    <option value="loa2">loa2</option>
                  </select>
                </div>
                <div className="form-group">
                  <label htmlFor="kc-native-amr">amr - nativeToolIds (kommagetrennt, method/loa kommen serverseitig aus der Descriptor-Registry)</label>
                  <input id="kc-native-amr" value={nativeToolIdsInput} onChange={(e) => setNativeToolIdsInput(e.target.value)} placeholder="kc-sms-form,kc-password-form" />
                </div>
              </div>
            )}

            <div className="form-actions">
              <button onClick={start}>Kanal starten</button>
            </div>
          </>
        )}

        {channelSessionId && (
          <p>
            <strong>channelSessionId:</strong> {channelSessionId} ({startMode === 'login' ? 'initialer Login' : 'Sub vorhanden'})
            <button className="secondary small" onClick={reset} style={{ marginLeft: '1rem' }}>
              Zurücksetzen
            </button>
          </p>
        )}

        {channelSessionId && response?.authData?.accountId != null && (
          <p className="hint">
            <button className="secondary small" onClick={fetchRestoreData}>
              RestoreData holen
            </button>{' '}
            simuliert den Lifecycle-Hook des Authenticators am Flow-Ende (docs/ideen/web-keycloak-kanal.md #6):
            {restoreDataToken ? ' Token vorhanden - wird beim nächsten "Sub vorhanden"-Start automatisch mitgeschickt.' : ' noch kein Token geholt.'}
          </p>
        )}
        {restoreError && <div className="error-card">{restoreError}</div>}

        {error && <div className="error-card">{error}</div>}
      </div>

      {channelSessionId && !authenticated && (
        <div className="card">
          <h3>Nativen Authenticator simulieren</h3>
          <p>
            Steht für einen nativ konfigurierten Keycloak-Schritt (kein Orchestrator-Tool) - z. B. den eingebauten
            Cookie-/OTP-Authenticator. Wird sofort in die Evidenz dieses Kanals gemischt und direkt danach mit einem
            separaten, reinen Resume-Aufruf (kein Tool, kein amr) bestätigt - so bleibt sichtbar, dass authData.amr
            (Methode → Quelle) auch unabhängig vom simulierenden Aufruf bestehen bleibt (docs/ideen/
            web-keycloak-kanal.md #8).
          </p>
          <div className="form-grid">
            <div className="form-group">
              <label htmlFor="kc-native-account-id">accountId</label>
              <input id="kc-native-account-id" value={accountIdInput} onChange={(e) => setAccountIdInput(e.target.value)} placeholder="siehe Demo-Tab, Sicherheitsdetails" />
            </div>
            <div className="form-group">
              <label htmlFor="kc-native-amr-2">amr - nativeToolIds (kommagetrennt)</label>
              <input id="kc-native-amr-2" value={nativeToolIdsInput} onChange={(e) => setNativeToolIdsInput(e.target.value)} placeholder="kc-sms-form,kc-password-form" />
            </div>
          </div>
          <div className="form-actions">
            <button onClick={simulateNativeAuthenticator}>Simulieren</button>
          </div>
        </div>
      )}

      {next?.type === 'orchestrator' && stepData?.options && (
        <SelectMethodView options={stepData.options} title={stepData.title ?? 'Verfahren wählen'} description={stepData.description} onSelect={selectMethod} />
      )}

      {next?.type === 'orchestrator' && stepData?.prompt && <p className="card">Prompt-Schritte werden im Mock nicht unterstützt (nur im App-Kanal via Answer-Endpunkt).</p>}

      {toolCtx && toolRenderable && (
        <>
          {renderToolStep(toolCtx)}
          <div className="form-actions">
            <button className="secondary" onClick={switchMethod}>
              Anderes Verfahren
            </button>
          </div>
        </>
      )}

      {authenticated && (
        <div className="card success-card">
          <h2>Angemeldet</h2>
          <p>Der Kanal ist AUTHENTICATED - Keycloak würde ab hier Required Actions/Claims aus dem letzten authData minten.</p>
        </div>
      )}

      {response && (
        <div className="card">
          <h3>authData (an Keycloak-Notes geschrieben)</h3>
          <pre>{JSON.stringify(response.authData ?? {}, null, 2)}</pre>
        </div>
      )}
    </>
  )
}
