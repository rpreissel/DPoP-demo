import { useCallback, useEffect, useRef, useState } from 'react'
import '../../App.css'
import { AdminToolAvailabilityView } from '../../components/AdminToolAvailabilityView'
import { DeveloperToolsCard } from '../../components/DeveloperToolsCard'
import { WebChannelLayout } from '../../components/WebChannelLayout'
import { WebChannelView } from '../../components/WebChannelView'
import { JourneyLogView } from '../../components/JourneyLogView'
import { KeycloakSyncView } from '../../components/KeycloakSyncView'
import { MockKeycloakView, type MockKeycloakState } from '../../components/MockKeycloakView'
import { DebugSidebar, type DebugEvent } from '../../components/DebugSidebar'
import { onKcApiCall } from '../../kcApi'
import { getWebJourneyLog } from '../../webApi'
import type { TokenSet } from '../../webOidc'

type SubTab = 'demo' | 'mock' | 'journeylog' | 'settings'
const SUB_TABS: SubTab[] = ['demo', 'mock', 'journeylog', 'settings']

/** Same reasoning as AppChannelApp's own subFromHash - the sub-tab lives in the URL hash so a reload/shared link/back-button lands on the right place. */
function subFromHash(): SubTab {
  const raw = window.location.hash.slice(1)
  return (SUB_TABS as string[]).includes(raw) ? (raw as SubTab) : 'demo'
}

/**
 * The WEB channel's own app (docs/10-frontend.md #1): a real browser client against a real
 * Keycloak - no orchestrator round-trip, no dpop key, entirely independent of AppChannelApp's
 * state (they are genuinely separate pages now, not sections of one SPA).
 */
export function WebChannelApp() {
  const [sub, setSubState] = useState<SubTab>(() => subFromHash())
  const [webTokens, setWebTokens] = useState<TokenSet | null>(null)
  const webJourneyLogFetcher = useCallback(() => getWebJourneyLog(webTokens!.accessToken), [webTokens])
  const [debugOpen, setDebugOpen] = useState(false)
  // Mock-Keycloak's own state/log - the debug sidebar sits at the SAME top-level position (app-shell
  // sibling of app-main) AppChannelApp's own DebugSidebar uses, not nested inside the narrower
  // content column, so it has real viewport width to work with.
  const [kcState, setKcState] = useState<MockKeycloakState>({})
  const [kcDebugLog, setKcDebugLog] = useState<DebugEvent[]>([])
  const kcDebugIdRef = useRef(0)

  function setActiveTab(newSub: SubTab) {
    setSubState(newSub)
    const hash = newSub === 'demo' ? '' : newSub
    if (window.location.hash.slice(1) !== hash) window.location.hash = hash
  }

  useEffect(() => {
    const onHashChange = () => setSubState(subFromHash())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  useEffect(() => {
    return onKcApiCall((entry) => {
      kcDebugIdRef.current += 1
      setKcDebugLog((prev) =>
        [
          { id: kcDebugIdRef.current, time: new Date().toLocaleTimeString(), label: `${entry.method} ${entry.path}`, request: entry.requestBody, response: entry.responseBody, error: entry.error },
          ...prev,
        ].slice(0, 200),
      )
    })
  }, [])

  return (
    <div className="app-shell">
      <div className="app-main">
        <WebChannelLayout sub={sub} onSelectTab={setActiveTab} onBack={() => { window.location.href = '/' }}>
          {sub === 'mock' && <MockKeycloakView onStateChange={setKcState} />}

          {sub === 'journeylog' && <JourneyLogView fetchLog={webTokens ? webJourneyLogFetcher : null} />}

          {sub === 'settings' && (
            <>
              <AdminToolAvailabilityView />
              <div className="card">
                <h2>Web-Kanal-Info</h2>
                <ul className="status-list">
                  <li>
                    <span className="label">Realm</span>
                    <span className="value">dpop-demo</span>
                  </li>
                  <li>
                    <span className="label">Client</span>
                    <span className="value">dpop-demo-web (public, PKCE)</span>
                  </li>
                  <li>
                    <span className="label">Keycloak</span>
                    <a className="value" href="https://localhost:8543" target="_blank" rel="noreferrer">
                      https://localhost:8543
                    </a>
                  </li>
                </ul>
              </div>
              <KeycloakSyncView />
              <DeveloperToolsCard />
            </>
          )}

          {sub === 'demo' && <WebChannelView onTokens={setWebTokens} />}
        </WebChannelLayout>
      </div>

      {sub === 'mock' && (
        <DebugSidebar
          channel={{ channelSessionId: kcState.channelSessionId, channelState: kcState.channelState, next: kcState.next, stepData: kcState.stepData, demo: kcState.demo, authData: kcState.authData }}
          log={kcDebugLog}
          open={debugOpen}
          onToggle={() => setDebugOpen((v) => !v)}
        />
      )}
    </div>
  )
}
