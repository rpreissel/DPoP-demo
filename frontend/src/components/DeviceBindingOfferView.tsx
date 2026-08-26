interface Props {
  onAnswer: (accept: boolean) => void
  busy?: boolean
}

/**
 * The one screen of a lookup login that is not a tool: after the credential is proven, the user
 * decides whether this device should be recognized next time. Never implied - this way in is
 * chosen precisely by people who do not want a device binding.
 */
export function DeviceBindingOfferView({ onAnswer, busy }: Props) {
  return (
    <div className="card">
      <h2>Dieses Gerät merken?</h2>
      <p className="muted">
        Wenn Sie zustimmen, erkennt der Dienst dieses Gerät beim nächsten Mal wieder und Sie
        müssen Ihre E-Mail-Adresse nicht erneut eingeben. Sie können auch ohne Bindung
        fortfahren – dann melden Sie sich künftig wieder über E-Mail und Passwort an.
      </p>
      <div className="actions">
        <button type="button" onClick={() => onAnswer(true)} disabled={busy}>
          Gerät merken
        </button>
        <button type="button" className="secondary" onClick={() => onAnswer(false)} disabled={busy}>
          Ohne Bindung fortfahren
        </button>
      </div>
    </div>
  )
}
