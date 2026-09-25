import { useEffect, useState } from 'react'
import '../../App.css'
import { fetchServerInfo, type KeycloakInfo } from '../../api'
import { WebChannelLayout } from '../../components/WebChannelLayout'
import { WebChannelView } from '../../components/WebChannelView'
import { t } from '../../texts'
import { Tx } from '../../Tx'

/**
 * The WEB channel's own app (docs/10-frontend.md #0): a real browser client against a real
 * Keycloak - no orchestrator round-trip, no dpop key, entirely independent of AppChannelApp's
 * state. It exists only with the `keycloak` Spring profile; without it there is no Keycloak to log
 * in at, so the page says so instead of offering a login that cannot work.
 */
export function WebChannelApp() {
  // undefined while the server status loads - neither the login nor the notice flashes up first;
  // null means no Keycloak. The same answer also says where Keycloak is for this browser.
  const [keycloak, setKeycloak] = useState<KeycloakInfo | null | undefined>(undefined)

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setKeycloak(info.keycloak ?? null))
      .catch(() => setKeycloak(null))
  }, [])

  return (
    <WebChannelLayout>
      {keycloak && <WebChannelView keycloak={keycloak} />}
      {keycloak === null && (
        <div className="web-page">
          <WebChannelUnavailable />
        </div>
      )}
    </WebChannelLayout>
  )
}

function WebChannelUnavailable() {
  return (
    <div className="card">
      <h2>{t('Web-Kanal nicht verfügbar')}</h2>
      <p>
        <Tx
          text="Der Web-Kanal meldet sich an einem echten Keycloak an. Der Server läuft gerade ohne das Spring-Profil {profil}, es gibt also kein Keycloak, an dem man sich anmelden könnte."
          profil={<code>keycloak</code>}
        />
      </p>
      <p className="hint">
        <Tx
          text="Starten mit {keycloak} und {server}."
          keycloak={<code>podman-compose up -d keycloak</code>}
          server={<code>./gradlew bootRunKc</code>}
        />
      </p>
    </div>
  )
}
