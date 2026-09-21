import type { DeviceLinkResponse } from '../types'
import { shorten } from '../format'

interface DeviceIdentityCardProps {
  jwkThumbprint?: string
  onRecreateKey: () => void
  /** null while still loading/unauthenticated-to-ask; DeviceLinkResponse once GET .../device-link answered. */
  deviceLink: DeviceLinkResponse | null
}

/**
 * FE-14: the device identity (JWK thumbprint) is its own card, always visible - independent of
 * whether a channel exists (unlike JourneyStructureView, which needs one). Also shows whose
 * account this device is durably linked to (DeviceAccountLink, docs/02-domaenenmodell.md #1) via
 * the read-only `GET .../device-link` - so the entry screen answers "whose device is this" before
 * the user even picks how to start, not just after.
 *
 * Below the DPoP key, one row per further binding this device carries
 * (`deviceLink.boundCredentials`): the `device` method's own credential key, the identifier KOBIL
 * gave this phone. Rendered generically from what the backend lists - each method decides what it
 * discloses, this card only prints it, so a new key-bound method needs no change here. They are
 * deliberately distinct concepts from the channel binding - see docs/09-dpop.md. Read from the
 * SAME `device-link` response as `deviceLink`
 * itself rather than from `demo` (which only ever exists once a channel/journey is running) -
 * this card renders precisely BEFORE any channel exists, so it needs data available at that
 * point too. Kept current automatically since `device-link` is re-fetched after every rebind/
 * enrollment/revocation that could change it (see the entry-screen refetch effect).
 */
export function DeviceIdentityCard({ jwkThumbprint, onRecreateKey, deviceLink }: DeviceIdentityCardProps) {
  const boundCredentials = deviceLink?.boundCredentials ?? []
  return (
    <div className="card device-identity-card">
      <ul className="status-list">
        <li>
          <span className="label">Geräte-Kennung (DPoP)</span>
          <span className="value-with-action">
            <span className="value" title={jwkThumbprint}>
              {shorten(jwkThumbprint)}
            </span>
            <button
              className="secondary small"
              onClick={onRecreateKey}
              title="Löscht diesen DPoP-Schlüssel und erzeugt einen neuen - das Gerät gilt danach als unbekannt, jede laufende Sitzung wird lokal verworfen."
            >
              Neu erzeugen
            </button>
          </span>
        </li>
        {boundCredentials.map((credential) => (
          <li key={credential.method}>
            <span className="label">Gerätebindung ({credential.method})</span>
            <span className="value" title={credential.reference}>
              {shorten(credential.reference)}
            </span>
          </li>
        ))}
        <li>
          <span className="label">Gebunden an</span>
          <span className="value">
            {deviceLink == null
              ? '…'
              : deviceLink.linked
                ? (deviceLink.personName ?? `Konto ${deviceLink.accountId}`)
                : 'noch keinem Konto zugeordnet'}
          </span>
        </li>
      </ul>
    </div>
  )
}
