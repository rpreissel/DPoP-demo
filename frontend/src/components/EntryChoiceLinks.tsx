interface EntryChoiceLinksProps {
  channelState?: string
  onChooseIntent: (intent: 'login' | 'register') => void
}

/**
 * Lets the user switch between registration, lookup-based login ("Login ohne DPoP") and a
 * second account on this device - independent of what DeviceAccountLink or the in-progress
 * process currently offer (docs/04-orchestrierung.md, lookup-based login). Each choice mints a
 * brand-new channel with the corresponding `intent` (docs/02-domaenenmodell.md #3: channels are
 * always cheap to re-create), abandoning whatever is currently in progress on this one.
 *
 * Shown whenever the channel isn't already AUTHENTICATED/LOGGED_OUT - deliberately not narrowed
 * to only the very first screen of REGISTRATION/LOGIN, since a user may legitimately want to
 * bail out of a multi-step flow (e.g. wrong account, forgotten TAN) and start over differently.
 */
export function EntryChoiceLinks({ channelState, onChooseIntent }: EntryChoiceLinksProps) {
  if (!channelState || channelState === 'AUTHENTICATED' || channelState === 'LOGGED_OUT') return null

  return (
    <div className="entry-choice-links">
      <button className="secondary" onClick={() => onChooseIntent('login')}>
        Ich habe schon einen Account (Login ohne DPoP)
      </button>
      <button className="secondary" onClick={() => onChooseIntent('register')}>
        Neuen Account registrieren
      </button>
    </div>
  )
}
