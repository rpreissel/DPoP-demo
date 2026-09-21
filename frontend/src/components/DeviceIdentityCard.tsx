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
 * Two further, demo-only values are shown alongside the DPoP key when present:
 * `deviceLink.deviceAuthKeyRef` (the `device` method's own credential key) and
 * `deviceLink.kobilDeviceId` (the identifier the external provider assigned to this device).
 * The first of those: the two are deliberately distinct concepts (channel binding vs. a `device`-method
 * credential) - see docs/09-dpop.md. Read from the SAME `device-link` response as `deviceLink`
 * itself rather than from `demo` (which only ever exists once a channel/journey is running) -
 * this card renders precisely BEFORE any channel exists, so it needs data available at that
 * point too. Kept current automatically since `device-link` is re-fetched after every rebind/
 * enrollment/revocation that could change it (see the entry-screen refetch effect).
 */
export function DeviceIdentityCard({ jwkThumbprint, onRecreateKey, deviceLink }: DeviceIdentityCardProps) {
  const deviceAuthKeyRef = deviceLink?.deviceAuthKeyRef
  const kobilDeviceId = deviceLink?.kobilDeviceId
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
        {deviceAuthKeyRef && (
          <li>
            <span className="label">Geräte-Anmeldeverfahren (Schlüssel)</span>
            <span className="value" title={deviceAuthKeyRef}>
              {shorten(deviceAuthKeyRef)}
            </span>
          </li>
        )}
        {/*
          The provider's own side of the binding: KOBIL assigned this identifier to this device,
          and the backend compares every redeemed assertion against it. Shown next to the two
          local keys because it is the third thing this device is known by - and the only one
          this application did not create itself.
        */}
        {kobilDeviceId && (
          <li>
            <span className="label">KOBIL-Gerätebindung</span>
            <span className="value" title={kobilDeviceId}>
              {shorten(kobilDeviceId)}
            </span>
          </li>
        )}
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
