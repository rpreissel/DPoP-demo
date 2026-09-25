import { useEffect, useState, type ReactNode } from 'react'
import { chooseLanguage, language, supportedLanguages, t } from '../texts'

/**
 * An implied smartphone: a rounded frame with a status bar, the app's dark blue head and a white
 * sheet that scrolls at phone width. Only what the real app would show goes in here (DemoArea).
 * The look follows a health insurer's app - head, sheet, soft cards, list rows with a chevron -
 * without any brand (phone.css).
 */
export function PhoneFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="phone">
      <div className="phone__screen">
        <div className="phone__status" aria-hidden="true">
          <PhoneClock />
          <span className="phone__status-icons">
            <i className="phone__signal" />
            <i className="phone__battery" />
          </span>
        </div>
        <header className="phone__head">
          <span className="phone__title">{title}</span>
          <LanguageSwitch />
        </header>
        <div className="phone__sheet">{children}</div>
        <div className="phone__home" aria-hidden="true" />
      </div>
    </div>
  )
}

/** DE | EN in the app's head, like a real app's language setting - applies to the whole demo. */
function LanguageSwitch() {
  const current = language()
  return (
    <div className="phone__languages" role="group" aria-label={t('Sprache')}>
      {supportedLanguages.map((lang) => (
        <button
          key={lang}
          className="phone__language"
          aria-pressed={lang === current}
          onClick={() => lang !== current && chooseLanguage(lang)}
        >
          {lang.toUpperCase()}
        </button>
      ))}
    </div>
  )
}

/** The status bar's clock: the real time, moving on at each full minute like a phone's. */
function PhoneClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | undefined
    // First tick at the next full minute, then every minute - so it changes when the phone's would.
    const timeout = setTimeout(() => {
      setNow(new Date())
      interval = setInterval(() => setNow(new Date()), 60_000)
    }, 60_000 - (Date.now() % 60_000))
    return () => {
      clearTimeout(timeout)
      if (interval) clearInterval(interval)
    }
  }, [])
  return <span>{now.toLocaleTimeString(language(), { hour: 'numeric', minute: '2-digit' })}</span>
}
